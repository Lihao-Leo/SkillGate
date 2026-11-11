package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AdminUser;
import com.skill.platform.gateway.dal.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.skill.platform.gateway.service.AdminAuthDtos.ChangePasswordRequest;
import static com.skill.platform.gateway.service.AdminAuthDtos.CreateUserRequest;
import static com.skill.platform.gateway.service.AdminAuthDtos.LoginRequest;
import static com.skill.platform.gateway.service.AdminAuthDtos.LoginResult;
import static com.skill.platform.gateway.service.AdminAuthDtos.UserView;

/**
 * 管理后台账号服务：BCrypt 哈希存储（spring-security-crypto，不引 Security 过滤器链）。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUserMapper adminUserMapper;
    private final AdminRoleService adminRoleService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${skill-platform.admin-jwt.secret}")
    private String jwtSecret;

    @Value("${skill-platform.admin-jwt.expire-hours:12}")
    private long expireHours;

    /** 登录：校验启用状态 + BCrypt 验证 + 回写 last_login_at。 */
    public LoginResult login(LoginRequest request) {
        if (request == null || isBlank(request.username()) || isBlank(request.password())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名与密码不能为空");
        }
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.username()).last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(update);

        String token = com.skill.platform.gateway.security.AdminJwtUtil.issue(
                jwtSecret, user.getUsername(), user.getRoleCode(), expireHours);
        return new LoginResult(token, expireHours, user.getUsername(), user.getDisplayName(),
                user.getRoleCode(), adminRoleService.roleNameOf(user.getRoleCode()),
                List.copyOf(adminRoleService.permissionsOf(user.getRoleCode())));
    }

    /** 当前会话画像（/auth/me）。 */
    public AdminAuthDtos.AdminProfile profile(String username) {
        AdminUser user = mustGet(username);
        return new AdminAuthDtos.AdminProfile(user.getUsername(), user.getDisplayName(),
                user.getRoleCode(), adminRoleService.roleNameOf(user.getRoleCode()),
                List.copyOf(adminRoleService.permissionsOf(user.getRoleCode())));
    }

    /** 改密（旧密码校验）。 */
    public void changePassword(String username, ChangePasswordRequest request) {
        if (request == null || isBlank(request.oldPassword()) || isBlank(request.newPassword())
                || request.newPassword().length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能为空且长度不少于 8 位");
        }
        AdminUser user = mustGet(username);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "旧密码错误");
        }
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        adminUserMapper.updateById(update);
    }

    /** 用户分页列表。 */
    public Map<String, Object> list(String keyword, int pageNo, int pageSize) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(AdminUser::getUsername, keyword.trim());
        }
        wrapper.orderByAsc(AdminUser::getId);
        Page<AdminUser> page = adminUserMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<UserView> rows = page.getRecords().stream().map(this::toView).toList();
        return Map.of("total", page.getTotal(), "pageNo", page.getCurrent(),
                "pageSize", page.getSize(), "rows", rows);
    }

    /** 创建用户。 */
    public UserView create(CreateUserRequest request) {
        if (request == null || isBlank(request.username()) || isBlank(request.password())
                || isBlank(request.roleCode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "username/password/roleCode 不能为空");
        }
        if (request.password().length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码长度不少于 8 位");
        }
        Long exists = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名已存在");
        }
        AdminUser user = new AdminUser();
        user.setUsername(request.username().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(isBlank(request.displayName()) ? request.username().trim() : request.displayName());
        user.setRoleCode(request.roleCode());
        user.setStatus(1);
        adminUserMapper.insert(user);
        return toView(user);
    }

    /** 改角色。 */
    public UserView changeRole(String username, String roleCode) {
        if (isBlank(roleCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "roleCode 不能为空");
        }
        AdminUser user = mustGet(username);
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setRoleCode(roleCode);
        adminUserMapper.updateById(update);
        return toView(mustGet(username));
    }

    /** 启停。 */
    public UserView updateStatus(String username, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅允许 0/1");
        }
        AdminUser user = mustGet(username);
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setStatus(status);
        adminUserMapper.updateById(update);
        return toView(mustGet(username));
    }

    /** 重置密码（新值仅此一次返回）。 */
    public Map<String, String> resetPassword(String username) {
        AdminUser user = mustGet(username);
        String newPassword = "Adm-" + UUID.randomUUID().toString().substring(0, 8);
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUserMapper.updateById(update);
        return Map.of("username", username, "password", newPassword);
    }

    private AdminUser mustGet(String username) {
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private UserView toView(AdminUser user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoleCode(), adminRoleService.roleNameOf(user.getRoleCode()),
                user.getStatus(), user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
