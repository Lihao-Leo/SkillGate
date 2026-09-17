package com.skill.platform.billing.service;

import com.skill.platform.billing.dto.BalanceView;
import com.skill.platform.billing.dto.FreezeResponse;
import com.skill.platform.billing.dto.HoldView;
import com.skill.platform.billing.dto.PageView;
import com.skill.platform.billing.dto.ReleaseResponse;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.dto.TransactionView;

/**
 * 计费核心服务（技术方案 v6.5 §4.8）：预校验-冻结-结算-退款闭环 + 不可变流水。
 *
 * <p>幂等约定：
 * <ul>
 *   <li>freeze 按 taskId 幂等（重复提交返回原冻结单）</li>
 *   <li>settle / release 按 hold 状态幂等（非 FROZEN 直接返回，不二次扣/退）</li>
 * </ul>
 */
public interface BillingService {

    /** 开户（gateway 签发 AppKey 时调用；已存在则幂等返回） */
    void ensureAccount(String appKeyId, String tenantId);

    /**
     * 冻结：balance → frozen 单行原子迁移；余额不足抛 40201，账户不存在抛 40401。
     */
    FreezeResponse freeze(String taskId, String appKeyId, long amount, String remark);

    /**
     * 结算：实际费用从 frozen 划扣、差额解冻；actualPoints 超出冻结额按封顶处理。
     * hold 非 FROZEN 时幂等返回（alreadySettled=true）。
     */
    SettleResponse settle(String taskId, long actualPoints);

    /** 按 holdId 释放（全额退款）。非 FROZEN 幂等返回。 */
    ReleaseResponse release(String holdId);

    /** 按 taskId 释放。非 FROZEN 幂等返回。 */
    ReleaseResponse releaseByTask(String taskId);

    /** 充值入账（recharge-service 支付成功后调用），账户不存在自动开户；按 (RECHARGE, orderNo) 幂等。 */
    com.skill.platform.billing.dto.RechargeResult recharge(String appKeyId, String tenantId,
                                                           long points, String orderNo);

    /** 人工调整：delta 可正可负，负向不得将余额调穿。 */
    BalanceView adjust(String appKeyId, long delta, String remark);

    /** 余额查询；账户不存在返回 0/0。 */
    BalanceView balance(String appKeyId);

    /** 冻结单查询（gateway 终态轮询组装 billing 块）。 */
    HoldView holdOf(String taskId);

    /** 流水分页查询，可按 taskId 过滤。 */
    PageView<TransactionView> transactions(String appKeyId, String taskId, int pageNo, int pageSize);
}
