package com.skill.platform.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.service.AdminRoleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 管理侧鉴权（V2.1 起）：双通道 + 权限点校验。
 *
 * <ol>
 *   <li>白名单：POST /api/v1/admin/auth/login 直接放行；</li>
 *   <li>优先 {@code Authorization: Bearer <jwt>}：验签后按角色组装权限点（30s 缓存）；</li>
 *   <li>兼容过渡：无 Bearer 时回落静态 {@code X-Admin-Token}（命中即视为 SUPER_ADMIN，
 *       供充值平台等存量调用方零改动；全部迁移后移除该分支并轮换 token）；</li>
 *   <li>方法/类上的 {@link RequirePermission} 任一权限点满足即放行，否则 40302。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String LOGIN_PATH = "/api/v1/admin/auth/login";

    private final ObjectMapper objectMapper;
    private final AdminRoleService adminRoleService;

    @Value("${skill-platform.admin-token}")
    private String adminToken;

    @Value("${skill-platform.admin-jwt.secret}")
    private String jwtSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (LOGIN_PATH.equals(request.getRequestURI())) {
            return true;
        }

        AdminContext ctx = resolve(request);
        if (ctx == null) {
            return reject(response, ErrorCode.UNAUTHORIZED, "未登录或凭证无效（JWT 过期 / admin token 错误）");
        }
        request.setAttribute(AdminContext.ATTR_ADMIN, ctx);

        String denied = checkPermission(handler, ctx);
        if (denied != null) {
            return reject(response, ErrorCode.PERMISSION_DENIED,
                    "无该操作权限（需要 " + denied + "）");
        }
        return true;
    }

    /** 双通道解析：Bearer JWT 优先，legacy 静态 token 兜底。 */
    private AdminContext resolve(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            AdminJwtUtil.AdminClaims claims = AdminJwtUtil.verify(
                    jwtSecret, authorization.substring("Bearer ".length()).trim());
            if (claims != null) {
                Set<String> perms = adminRoleService.permissionsOf(claims.role());
                return new AdminContext(claims.username(), claims.role(), perms);
            }
        }
        String legacy = request.getHeader("X-Admin-Token");
        if (legacy != null && legacy.equals(adminToken)) {
            return new AdminContext("legacy-token", Permissions.SUPER_ADMIN, Permissions.all());
        }
        return null;
    }

    /** 权限点校验：方法注解优先，其次类注解；返回缺失的权限点（null=放行）。 */
    private String checkPermission(Object handler, AdminContext ctx) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequirePermission required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequirePermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequirePermission.class);
        }
        if (required == null) {
            return null;
        }
        for (String perm : required.value()) {
            if (Permissions.matches(ctx, perm)) {
                return null;
            }
        }
        return String.join("/", required.value());
    }

    private boolean reject(HttpServletResponse response, ErrorCode errorCode, String message)
            throws java.io.IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.of(errorCode, message, null)));
        return false;
    }
}
