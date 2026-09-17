package com.skill.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 账户开户请求（gateway 签发 AppKey 时同步开户，V1 key 即账户）。
 */
public record AccountEnsureRequest(
        @NotBlank(message = "appKeyId 不能为空") String appKeyId,
        @NotBlank(message = "tenantId 不能为空") String tenantId) {
}
