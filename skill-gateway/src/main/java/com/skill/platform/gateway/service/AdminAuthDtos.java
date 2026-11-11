package com.skill.platform.gateway.service;

import java.util.List;
import java.util.Map;

/**
 * 后台账号 / 登录 DTO（record 风格，与 ExecuteDtos/SkillDtos 同惯例）。
 */
public final class AdminAuthDtos {

    private AdminAuthDtos() {
    }

    /** 登录入参。 */
    public record LoginRequest(String username, String password) {
    }

    /** 登录出参：JWT + 会话画像（前端菜单/按钮渲染数据源）。 */
    public record LoginResult(String token, long expiresInHours, String username,
                              String displayName, String role, String roleName,
                              List<String> permissions) {
    }

    /** 当前登录者画像。 */
    public record AdminProfile(String username, String displayName, String role,
                               String roleName, List<String> permissions) {
    }

    /** 改密入参。 */
    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    /** 用户管理出参（不含密码哈希）。 */
    public record UserView(Long id, String username, String displayName, String role,
                           String roleName, Integer status, String lastLoginAt) {
    }

    /** 创建用户入参。 */
    public record CreateUserRequest(String username, String password, String displayName,
                                    String roleCode) {
    }

    /** 角色视图。 */
    public record RoleView(String roleCode, String roleName, String remark, Integer builtin,
                           List<String> permissions) {
    }

    /** 权限点视图。 */
    public record PermissionView(String permCode, String permName, String groupName, Integer sortNo) {
    }
}
