package com.skill.platform.gateway.billing;

import com.skill.platform.gateway.billing.BillingViews.BalanceView;
import com.skill.platform.gateway.billing.BillingViews.FreezeResult;
import com.skill.platform.gateway.billing.BillingViews.RechargeResult;
import com.skill.platform.gateway.billing.BillingViews.SettlementView;
import com.skill.platform.gateway.billing.BillingViews.TransactionPage;

/**
 * billing-service 同步 RPC 端口（受理冻结主路径 / 终态结算状态查询 / 账户查询与充值入账代理）。
 */
public interface BillingClient {

    void ensureAccount(String appKeyId, String tenantId);

    /** 冻结（按 taskId 幂等）；余额不足抛 40201，账户不存在抛 40401 */
    FreezeResult freeze(String taskId, String appKeyId, long amount);

    /** 按 holdId 释放（受理侧回滚） */
    void release(String holdId);

    /** 按 taskId 释放（PENDING 取消退款） */
    void releaseByTask(String taskId);

    /** 冻结单状态（终态查询 billing 块）；无冻结单返回 null（FREE/0 元任务） */
    SettlementView settlementOf(String taskId);

    BalanceView balance(String appKeyId);

    TransactionPage transactions(String appKeyId, String taskId, int pageNo, int pageSize);

    /** 充值入账（orderNo 幂等，重复调用返回已入账流水） */
    RechargeResult recharge(String appKeyId, String tenantId, long points, String orderNo);
}
