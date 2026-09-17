package com.skill.platform.billing.dto;

/**
 * 冻结结果。
 */
public record FreezeResponse(String holdId, long amount, boolean alreadyFrozen) {
}
