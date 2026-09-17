package com.skill.platform.billing.dto;

/**
 * 释放请求（受理侧回滚 / 对账兜底退款）：holdId 与 taskId 至少传一个。
 */
public record ReleaseRequest(String holdId, String taskId) {

    public boolean hasKey() {
        return (holdId != null && !holdId.isBlank()) || (taskId != null && !taskId.isBlank());
    }
}
