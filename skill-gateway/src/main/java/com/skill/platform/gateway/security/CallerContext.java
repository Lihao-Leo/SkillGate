package com.skill.platform.gateway.security;

/**
 * 调用方上下文：AppKey 鉴权成功后挂在 request attribute，供后续关卡与 Service 使用。
 */
public record CallerContext(String appKeyId, String tenantId) {
}
