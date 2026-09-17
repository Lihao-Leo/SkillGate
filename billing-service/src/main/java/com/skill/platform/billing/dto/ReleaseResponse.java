package com.skill.platform.billing.dto;

/**
 * 释放结果。alreadyReleased=true 表示重复消息命中幂等。
 */
public record ReleaseResponse(String holdId, String taskId, String status, boolean alreadyReleased) {
}
