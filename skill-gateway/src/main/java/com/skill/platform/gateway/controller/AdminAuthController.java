package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AdminContext;
import com.skill.platform.gateway.service.AdminAuthDtos.AdminProfile;
import com.skill.platform.gateway.service.AdminAuthDtos.ChangePasswordRequest;
import com.skill.platform.gateway.service.AdminAuthDtos.LoginRequest;
import com.skill.platform.gateway.service.AdminAuthDtos.LoginResult;
import com.skill.platform.gateway.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台登录与会话（V2.1）：login 白名单放行（见 AdminAuthInterceptor），me/password 需已登录。
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserService adminUserService;

    /** 账号密码登录（BCrypt 校验）→ JWT + 会话画像。 */
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(adminUserService.login(request));
    }

    /** 当前登录者画像（含权限点，前端菜单/按钮渲染数据源）。 */
    @GetMapping("/me")
    public ApiResponse<AdminProfile> me(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin) {
        return ApiResponse.ok(adminUserService.profile(admin.username()));
    }

    /** 修改自己的密码（旧密码校验）。 */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
            @RequestBody ChangePasswordRequest request) {
        adminUserService.changePassword(admin.username(), request);
        return ApiResponse.ok(null);
    }
}
