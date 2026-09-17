package com.skill.platform.billing.dto;

import java.time.LocalDateTime;

/**
 * 对账扫描行：FROZEN 超 24h 的冻结单 + 任务状态（LEFT JOIN，任务可能不存在）。
 */
public record OverdueHold(String holdId, String taskId, String appKeyId, long amount,
                          LocalDateTime createdAt, String execStatus, Integer expectedCount,
                          String pricingSnapshot) {
}
