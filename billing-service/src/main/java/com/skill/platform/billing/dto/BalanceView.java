package com.skill.platform.billing.dto;

/**
 * 账户余额视图（GET /api/v1/account/balance 数据源）。
 */
public record BalanceView(String appKeyId, long balance, long frozen) {

    public static BalanceView empty(String appKeyId) {
        return new BalanceView(appKeyId, 0L, 0L);
    }
}
