package com.skill.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 结算请求（worker 同步 RPC 主路径 / skill-settle MQ 兜底，按 taskId 幂等）。
 *
 * @param actualPoints 实际扣点：PER_EXECUTION = 单价 × min(实际产物数, count)；
 *                     失败/取消/空产物 = 0；METERED = usage 折算（≤ capPoints）
 */
public record SettleRequest(
        @NotBlank(message = "taskId 不能为空") String taskId,
        @PositiveOrZero(message = "实际扣点不能为负") long actualPoints) {
}
