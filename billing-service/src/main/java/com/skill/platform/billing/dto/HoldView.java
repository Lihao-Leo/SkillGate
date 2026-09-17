package com.skill.platform.billing.dto;

import java.time.LocalDateTime;

/**
 * 冻结单视图（gateway 终态查询组装 billing 块用）。
 */
public record HoldView(String holdId, String taskId, String appKeyId, long amount,
                       String status, Long settledAmount, LocalDateTime createdAt,
                       LocalDateTime settledAt) {
}
