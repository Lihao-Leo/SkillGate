package com.skill.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 人工调整请求（运营后台）：delta 可正可负，负向不允许将余额调穿。
 */
public record AdjustRequest(
        @NotBlank(message = "appKeyId 不能为空") String appKeyId,
        @NotNull(message = "delta 不能为空") Long delta,
        String remark) {
}
