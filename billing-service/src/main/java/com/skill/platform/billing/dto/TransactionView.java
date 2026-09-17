package com.skill.platform.billing.dto;

import java.time.LocalDateTime;

/**
 * 计费流水视图（GET /api/v1/account/transactions 数据源）。
 */
public record TransactionView(String txId, String type, String taskId, long amount,
                              long balanceAfter, String remark, LocalDateTime createdAt) {
}
