package com.skill.platform.gateway.security;

import java.util.Set;

/**
 * 后台操作者上下文（JWT 或 legacy 静态 token 解析后挂 request attribute）。
 *
 * @param username    登录名（legacy token 固定 "legacy-token"）
 * @param role        角色编码
 * @param permissions 权限点集合（SUPER_ADMIN 为通配，见 {@link Permissions#matches}）
 */
public record AdminContext(String username, String role, Set<String> permissions) {

    public static final String ATTR_ADMIN = "gateway.admin";
}
