package com.skill.platform.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.skill.platform.billing.dto.RechargeResult;
import com.skill.platform.billing.dto.ReleaseResponse;
import com.skill.platform.billing.dto.SettleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 计费事务原语：每个方法一个事务，动账（账户单行原子更新）+ hold 状态 + 不可变流水
 * 三者同事务落库。上层 {@link BillingServiceImpl} 负责幂等与并发恢复。
 *
 * <p>金额不变量：settle 实扣 = min(actualPoints, hold.amount)，结算永不超冻结；
 * release 全额回流；流水 balance_after 与账户终态严格一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingTxService {

    private final CreditAccountMapper accountMapper;
    private final BillingHoldMapper holdMapper;
    private final BillingTransactionMapper transactionMapper;

    /**
     * 开户：不存在则插入 0 余额账户；并发插入冲突时幂等返回。
     */
    @Transactional
    public void ensureAccount(String appKeyId, String tenantId) {
        if (selectAccount(appKeyId) != null) {
            return;
        }
        CreditAccount account = new CreditAccount();
        account.setAppKeyId(appKeyId);
        account.setTenantId(tenantId);
        account.setBalance(0L);
        account.setFrozen(0L);
        account.setVersion(0L);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            log.info("account already created concurrently: {}", appKeyId);
        }
    }

    /**
     * 冻结（§4.8 单行原子 SQL）：影响行数 0 → 账户不存在(40401) 或余额不足(40201)。
     */
    @Transactional
    public FreezeResponse doFreeze(String taskId, String appKeyId, long amount, String remark) {
        int updated = accountMapper.freeze(appKeyId, amount);
        if (updated == 0) {
            CreditAccount account = selectAccount(appKeyId);
            if (account == null) {
                throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "点数账户不存在: " + appKeyId);
            }
            throw BizException.of(ErrorCode.INSUFFICIENT_BALANCE,
                    "点数余额不足：需冻结 " + amount + "，可用 " + account.getBalance());
        }
        BillingHold hold = insertHold(taskId, appKeyId, amount);
        insertTx(appKeyId, taskId, BillingTransaction.TYPE_HOLD, -amount,
                accountMapper.selectBalanceForUpdate(appKeyId), remark);
        return new FreezeResponse(hold.getHoldId(), amount, false);
    }

    /**
     * 结算：SELECT hold FOR UPDATE → 非 FROZEN 幂等返回；实扣封顶冻结额。
     */
    @Transactional
    public SettleResponse doSettle(String taskId, long actualPoints) {
        BillingHold hold = holdMapper.selectByTaskIdForUpdate(taskId);
        if (hold == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "冻结单不存在: " + taskId);
        }
        if (!BillingHold.STATUS_FROZEN.equals(hold.getStatus())) {
            return new SettleResponse(hold.getHoldId(), taskId,
                    hold.getSettledAmount() == null ? 0 : hold.getSettledAmount(),
                    hold.getStatus(), true);
        }
        long charged = Math.min(Math.max(actualPoints, 0), hold.getAmount());
        long refund = hold.getAmount() - charged;
        accountMapper.settle(hold.getAppKeyId(), hold.getAmount(), refund);
        markHoldSettled(hold, charged);
        insertTx(hold.getAppKeyId(), taskId, BillingTransaction.TYPE_SETTLE, -charged,
                accountMapper.selectBalanceForUpdate(hold.getAppKeyId()),
                "settle: charged=" + charged + ", refund=" + refund);
        log.info("settle done: taskId={}, hold={}, charged={}, refund={}",
                taskId, hold.getHoldId(), charged, refund);
        return new SettleResponse(hold.getHoldId(), taskId, charged, BillingHold.STATUS_SETTLED, false);
    }

    @Transactional
    public ReleaseResponse doReleaseByHoldId(String holdId) {
        BillingHold hold = holdMapper.selectByHoldIdForUpdate(holdId);
        if (hold == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "冻结单不存在: " + holdId);
        }
        return releaseLocked(hold);
    }

    @Transactional
    public ReleaseResponse doReleaseByTaskId(String taskId) {
        BillingHold hold = holdMapper.selectByTaskIdForUpdate(taskId);
        if (hold == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "冻结单不存在: " + taskId);
        }
        return releaseLocked(hold);
    }

    private ReleaseResponse releaseLocked(BillingHold hold) {
        if (!BillingHold.STATUS_FROZEN.equals(hold.getStatus())) {
            return new ReleaseResponse(hold.getHoldId(), hold.getTaskId(), hold.getStatus(), true);
        }
        accountMapper.release(hold.getAppKeyId(), hold.getAmount());
        hold.setStatus(BillingHold.STATUS_RELEASED);
        hold.setSettledAt(LocalDateTime.now());
        holdMapper.updateById(hold);
        insertTx(hold.getAppKeyId(), hold.getTaskId(), BillingTransaction.TYPE_RELEASE, hold.getAmount(),
                accountMapper.selectBalanceForUpdate(hold.getAppKeyId()), "release: full refund");
        log.info("release done: taskId={}, hold={}, amount={}",
                hold.getTaskId(), hold.getHoldId(), hold.getAmount());
        return new ReleaseResponse(hold.getHoldId(), hold.getTaskId(), BillingHold.STATUS_RELEASED, false);
    }

    /**
     * 充值：账户不存在自动开户（携带租户信息），入账 + RECHARGE 流水（orderNo 幂等键）。
     * (type, orderNo) 唯一冲突时事务整体回滚（余额不变），由上层返回已入账流水。
     */
    @Transactional
    public RechargeResult doRecharge(String appKeyId, String tenantId, long points, String orderNo) {
        ensureAccount(appKeyId, tenantId);
        accountMapper.addBalance(appKeyId, points);
        String txId = insertTx(appKeyId, null, BillingTransaction.TYPE_RECHARGE, points,
                accountMapper.selectBalanceForUpdate(appKeyId), "recharge: orderNo=" + orderNo, orderNo);
        CreditAccount account = selectAccount(appKeyId);
        return new RechargeResult(txId, appKeyId, points,
                account.getBalance(), account.getFrozen(), false);
    }

    /**
     * 人工调整：负向不得调穿余额（行锁下校验）。
     */
    @Transactional
    public BalanceView doAdjust(String appKeyId, long delta, String remark) {
        CreditAccount account = accountMapper.selectOne(new LambdaQueryWrapper<CreditAccount>()
                .eq(CreditAccount::getAppKeyId, appKeyId));
        if (account == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "点数账户不存在: " + appKeyId);
        }
        Long balance = accountMapper.selectBalanceForUpdate(appKeyId);
        if (balance == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "点数账户不存在: " + appKeyId);
        }
        if (balance + delta < 0) {
            throw BizException.of(ErrorCode.BILLING_VALIDATION_FAILED,
                    "调整后余额为负：当前 " + balance + "，delta " + delta);
        }
        accountMapper.addBalance(appKeyId, delta);
        insertTx(appKeyId, null, BillingTransaction.TYPE_ADJUST, delta,
                accountMapper.selectBalanceForUpdate(appKeyId), remark);
        return balanceOf(appKeyId);
    }

    private BillingHold insertHold(String taskId, String appKeyId, long amount) {
        BillingHold hold = new BillingHold();
        hold.setHoldId(Ids.next("hold_"));
        hold.setTaskId(taskId);
        hold.setAppKeyId(appKeyId);
        hold.setAmount(amount);
        hold.setStatus(BillingHold.STATUS_FROZEN);
        holdMapper.insert(hold);
        return hold;
    }

    private void markHoldSettled(BillingHold hold, long charged) {
        hold.setStatus(BillingHold.STATUS_SETTLED);
        hold.setSettledAmount(charged);
        hold.setSettledAt(LocalDateTime.now());
        holdMapper.updateById(hold);
    }

    private void insertTx(String appKeyId, String taskId, String type, long amount,
                          Long balanceAfter, String remark) {
        insertTx(appKeyId, taskId, type, amount, balanceAfter, remark, null);
    }

    private String insertTx(String appKeyId, String taskId, String type, long amount,
                            Long balanceAfter, String remark, String orderNo) {
        BillingTransaction transaction = new BillingTransaction();
        transaction.setTxId(Ids.next("tx_"));
        transaction.setAppKeyId(appKeyId);
        transaction.setTaskId(taskId);
        transaction.setType(type);
        transaction.setAmount(amount);
        transaction.setBalanceAfter(balanceAfter == null ? 0 : balanceAfter);
        transaction.setRemark(remark);
        transaction.setOrderNo(orderNo);
        transactionMapper.insert(transaction);
        return transaction.getTxId();
    }

    private CreditAccount selectAccount(String appKeyId) {
        return accountMapper.selectOne(new LambdaQueryWrapper<CreditAccount>()
                .eq(CreditAccount::getAppKeyId, appKeyId));
    }

    private BalanceView balanceOf(String appKeyId) {
        CreditAccount account = selectAccount(appKeyId);
        return account == null ? BalanceView.empty(appKeyId)
                : new BalanceView(appKeyId, account.getBalance(), account.getFrozen());
    }
}
