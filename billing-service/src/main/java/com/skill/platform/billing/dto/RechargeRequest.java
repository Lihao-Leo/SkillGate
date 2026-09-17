package com.skill.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 充值入账请求（recharge-service 支付成功后调用；orderNo 幂等键）。
 */
public record RechargeRequest(
        @NotBlank(message = "appKeyId 不能为空") String appKeyId,
        @NotBlank(message = "orderNo 不能为空") String orderNo,
        @NotNull @Positive(message = "充值点数必须大于 0") Long points,
        String tenantId) {
}
