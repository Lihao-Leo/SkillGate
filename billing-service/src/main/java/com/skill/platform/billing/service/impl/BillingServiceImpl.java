package com.skill.platform.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.billing.common.BizException;
import com.skill.platform.billing.common.ErrorCode;
import com.skill.platform.billing.common.Ids;
import com.skill.platform.billing.dal.entity.BillingHold;
import com.skill.platform.billing.dal.entity.BillingTransaction;
import com.skill.platform.billing.dal.entity.CreditAccount;
import com.skill.platform.billing.dal.mapper.BillingHoldMapper;
import com.skill.platform.billing.dal.mapper.BillingTransactionMapper;
import com.skill.platform.billing.dal.mapper.CreditAccountMapper;
import com.skill.platform.billing.dto.BalanceView;
import com.skill.platform.billing.dto.FreezeResponse;
import com.skill.platform.billing.dto.HoldView;
import com.skill.platform.billing.dto.PageView;
import com.skill.platform.billing.dto.RechargeResult;
import com.skill.platform.billing.dto.ReleaseResponse;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.dto.TransactionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 计费核心实现。结构：本类负责幂等前置检查与并发恢复；
 * {@link BillingTxService} 承载事务原语（单行原子更新 + hold + 流水同事务落库）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements com.skill.platform.billing.service.BillingService {

    private final BillingTxService tx;
    private final CreditAccountMapper accountMapper;
    private final BillingHoldMapper holdMapper;
    private final BillingTransactionMapper transactionMapper;

    @Override
    public void ensureAccount(String appKeyId, String tenantId) {
        tx.ensureAccount(appKeyId, tenantId);
    }

    @Override
    public FreezeResponse freeze(String taskId, String appKeyId, long amount, String remark) {
        BillingHold existing = holdMapper.selectByTaskId(taskId);
        if (existing != null) {
            return reuseExistingHold(existing, appKeyId);
        }
        try {
            return tx.doFreeze(taskId, appKeyId, amount, remark);
        } catch (DuplicateKeyException e) {
            // 并发同 taskId 双冻结：唯一键冲突一方整体回滚（含账户原子更新），再读胜者冻结单
            BillingHold winner = holdMapper.selectByTaskId(taskId);
            if (winner != null) {
                return reuseExistingHold(winner, appKeyId);
            }
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "冻结并发冲突且无法读取已有冻结单: " + taskId);
        }
    }

    private FreezeResponse reuseExistingHold(BillingHold hold, String appKeyId) {
        if (!hold.getAppKeyId().equals(appKeyId)) {
            throw BizException.of(ErrorCode.BILLING_VALIDATION_FAILED, "taskId 已绑定其他账户");
        }
        if (!BillingHold.STATUS_FROZEN.equals(hold.getStatus())) {
            throw BizException.of(ErrorCode.BILLING_VALIDATION_FAILED, "taskId 冻结单已终态: " + hold.getStatus());
        }
        return new FreezeResponse(hold.getHoldId(), hold.getAmount(), true);
    }

    @Override
    public SettleResponse settle(String taskId, long actualPoints) {
        return tx.doSettle(taskId, actualPoints);
    }

    @Override
    public ReleaseResponse release(String holdId) {
        return tx.doReleaseByHoldId(holdId);
    }

    @Override
    public ReleaseResponse releaseByTask(String taskId) {
        return tx.doReleaseByTaskId(taskId);
    }

    /**
     * 充值入账（orderNo 幂等）：唯一约束冲突方事务整体回滚（余额不变），
     * 返回已入账流水（alreadyCredited=true）——重复回调 / 重试任务安全重放。
     */
    @Override
    public RechargeResult recharge(String appKeyId, String tenantId, long points, String orderNo) {
        try {
            return tx.doRecharge(appKeyId, tenantId, points, orderNo);
        } catch (DuplicateKeyException e) {
            BillingTransaction existing = transactionMapper.selectOne(
                    new LambdaQueryWrapper<BillingTransaction>()
                            .eq(BillingTransaction::getType, BillingTransaction.TYPE_RECHARGE)
                            .eq(BillingTransaction::getOrderNo, orderNo)
                            .last("LIMIT 1"));
            if (existing == null || !existing.getAppKeyId().equals(appKeyId)) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "充值幂等冲突且无法读取已入账流水: " + orderNo);
            }
            CreditAccount account = selectAccount(appKeyId);
            log.info("recharge idempotent hit: orderNo={}, txId={}", orderNo, existing.getTxId());
            return new RechargeResult(existing.getTxId(), appKeyId, existing.getAmount(),
                    account == null ? 0 : account.getBalance(),
                    account == null ? 0 : account.getFrozen(), true);
        }
    }

    @Override
    public BalanceView adjust(String appKeyId, long delta, String remark) {
        return tx.doAdjust(appKeyId, delta, remark);
    }

    @Override
    public BalanceView balance(String appKeyId) {
        CreditAccount account = selectAccount(appKeyId);
        return account == null ? BalanceView.empty(appKeyId)
                : new BalanceView(appKeyId, account.getBalance(), account.getFrozen());
    }

    @Override
    public HoldView holdOf(String taskId) {
        BillingHold hold = holdMapper.selectByTaskId(taskId);
        return hold == null ? null : toView(hold);
    }

    @Override
    public PageView<TransactionView> transactions(String appKeyId, String taskId, int pageNo, int pageSize) {
        long safePageNo = Math.max(pageNo, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<BillingTransaction> wrapper = new LambdaQueryWrapper<BillingTransaction>()
                .eq(BillingTransaction::getAppKeyId, appKeyId)
                .eq(taskId != null && !taskId.isBlank(), BillingTransaction::getTaskId, taskId)
                .orderByDesc(BillingTransaction::getId);
        Page<BillingTransaction> pageParam = new Page<>(safePageNo, safePageSize);
        List<BillingTransaction> rows = transactionMapper.selectList(pageParam, wrapper);
        List<TransactionView> items = rows.stream()
                .map(r -> new TransactionView(r.getTxId(), r.getType(), r.getTaskId(), r.getAmount(),
                        r.getBalanceAfter(), r.getRemark(), r.getCreatedAt()))
                .toList();
        return new PageView<>(pageParam.getTotal(), safePageNo, safePageSize, items);
    }

    private CreditAccount selectAccount(String appKeyId) {
        return accountMapper.selectOne(new LambdaQueryWrapper<CreditAccount>()
                .eq(CreditAccount::getAppKeyId, appKeyId));
    }

    private HoldView toView(BillingHold h) {
        return new HoldView(h.getHoldId(), h.getTaskId(), h.getAppKeyId(), h.getAmount(), h.getStatus(),
                h.getSettledAmount(), h.getCreatedAt(), h.getSettledAt());
    }
}
