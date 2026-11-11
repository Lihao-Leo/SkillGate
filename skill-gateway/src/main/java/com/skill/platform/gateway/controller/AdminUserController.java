package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AdminContext;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.service.AdminAuthDtos.CreateUserRequest;
import com.skill.platform.gateway.service.AdminAuthDtos.PermissionView;
import com.skill.platform.gateway.service.AdminAuthDtos.RoleView;
import com.skill.platform.gateway.service.AdminAuthDtos.UserView;
import com.skill.platform.gateway.service.AdminRoleService;
import com.skill.platform.gateway.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台用户 / 角色管理（user:manage）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@RequirePermission(Permissions.USER_MANAGE)
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AdminRoleService adminRoleService;

    /** 用户分页列表。 */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(adminUserService.list(keyword, pageNo, pageSize));
    }

    /** 创建用户。 */
    @PostMapping("/users")
    public ApiResponse<UserView> createUser(@RequestBody UserPayload payload) {
        return ApiResponse.ok(adminUserService.create(new CreateUserRequest(
                payload.username(), payload.password(), payload.displayName(),
                payload.roleCode())));
    }

    /** 改角色。 */
    @PutMapping("/users/{username}/role")
    public ApiResponse<UserView> changeRole(@PathVariable String username,
                                            @RequestBody Map<String, String> request) {
        return ApiResponse.ok(adminUserService.changeRole(username, request.get("roleCode")));
    }

    /** 启停（0=禁用 1=启用）。 */
    @PutMapping("/users/{username}/status")
    public ApiResponse<UserView> updateStatus(@PathVariable String username,
                                              @RequestBody Map<String, Object> request) {
        Object status = request.get("status");
        return ApiResponse.ok(adminUserService.updateStatus(username,
                status instanceof Number number ? number.intValue() : null));
    }

    /** 重置密码（新值仅此一次返回）。 */
    @PostMapping("/users/{username}/reset-password")
    public ApiResponse<Map<String, String>> resetPassword(@PathVariable String username) {
        return ApiResponse.ok(adminUserService.resetPassword(username));
    }

    /** 角色列表（含权限点）。 */
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> listRoles() {
        return ApiResponse.ok(adminRoleService.listRoles().stream()
                .map(view -> new RoleView(
                        (String) view.get("roleCode"), (String) view.get("roleName"),
                        (String) view.get("remark"), (Integer) view.get("builtin"),
                        ((List<?>) view.get("permissions")).stream()
                                .map(String::valueOf).toList()))
                .toList());
    }

    /** 权限点目录。 */
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionView>> listPermissions() {
        return ApiResponse.ok(adminRoleService.listPermissions().stream()
                .map(view -> new PermissionView(
                        (String) view.get("permCode"), (String) view.get("permName"),
                        (String) view.get("groupName"), (Integer) view.get("sortNo")))
                .toList());
    }

    /** 创建用户入参。 */
    public record UserPayload(String username, String password, String displayName, String roleCode) {
    }
}
