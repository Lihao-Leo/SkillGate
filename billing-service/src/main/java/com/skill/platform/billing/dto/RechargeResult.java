package com.skill.platform.billing.dto;

/**
 * 充值入账结果。alreadyCredited=true 表示 orderNo 已入账（幂等命中，返回已入账流水）。
 */
public record RechargeResult(String txId, String appKeyId, long points,
                             long balance, long frozen, boolean alreadyCredited) {
}
