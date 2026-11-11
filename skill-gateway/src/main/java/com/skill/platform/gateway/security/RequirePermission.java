package com.skill.platform.gateway.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端点权限点声明（类或方法级；方法级优先）。多值 = 任一满足即放行。
 * 由 {@link AdminAuthInterceptor} 在 preHandle 中校验，不足返回 40302。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    String[] value();
}
