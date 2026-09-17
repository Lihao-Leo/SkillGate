package com.skill.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 冻结请求（gateway 受理阶段同步调用）。按 taskId 幂等：重复提交返回原冻结单。
 */
public record FreezeRequest(
        @NotBlank(message = "taskId 不能为空") String taskId,
        @NotBlank(message = "appKeyId 不能为空") String appKeyId,
        @Positive(message = "冻结点数必须大于 0") long amount,
        String remark) {
}
