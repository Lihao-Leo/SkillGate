package com.skill.platform.billing.dto;

/**
 * 结算结果。alreadySettled=true 表示重复消息命中幂等（hold 已终态），本次未动账。
 */
public record SettleResponse(String holdId, String taskId, long pointsCharged,
                             String status, boolean alreadySettled) {
}
