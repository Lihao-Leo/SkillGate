package com.skill.platform.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理侧服务间认证（§5.7）：/api/v1/admin/** 仅充值平台 / 运营后台调用，
 * V1 静态 token（部署形态：内网可达 + IP 白名单；演进 mTLS）。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Value("${skill-platform.admin-token}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String token = request.getHeader("X-Admin-Token");
        if (token != null && token.equals(adminToken)) {
            return true;
        }
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.of(ErrorCode.UNAUTHORIZED, "admin token 无效", null)));
        return false;
    }
}
