package com.skill.platform.recharge.security;

/**
 * 已登录用户上下文（JWT 过滤器解析后挂 request attribute）。
 */
public record UserContext(long userId, String identifier) {
}
