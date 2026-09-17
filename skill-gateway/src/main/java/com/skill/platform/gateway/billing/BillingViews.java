package com.skill.platform.gateway.billing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费服务视图（gateway 侧副本，与 billing-service 内部 API 契约对应）。
 */
public final class BillingViews {

    private BillingViews() {
    }

    public record FreezeResult(String holdId, long amount, boolean alreadyFrozen) {
    }

    /**
     * 冻结单状态（终态查询组装 billing 块）：SETTLED=已最终扣点；RELEASED=已全额退款；
     * FROZEN=在途（结算 RPC 降级时回调带预估值，settled=false）。
     */
    public record SettlementView(String holdId, String status, Long settledAmount) {
    }

    /** 充值入账结果（orderNo 幂等：alreadyCredited=true 为重复入账返回已入账流水） */
    public record RechargeResult(String txId, String appKeyId, long points,
                                 long balance, long frozen, boolean alreadyCredited) {
    }

    public record BalanceView(String appKeyId, long balance, long frozen) {
    }

    public record TransactionView(String txId, String type, String taskId, long amount,
                                  long balanceAfter, String remark, LocalDateTime createdAt) {
    }

    public record TransactionPage(long total, long pageNo, long pageSize,
                                   List<TransactionView> items) {
    }
}
