package com.skill.platform.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.billing.common.ApiResponse;
import com.skill.platform.billing.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 服务间认证：X-Internal-Token 与配置一致才放行；否则 40101。
 * V1 为静态 token，部署形态上 /internal/** 仅内网 Service 可达（NetworkPolicy / 安全组）。
 */
@Component
@RequiredArgsConstructor
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Value("${skill-billing.internal-token}")
    private String internalToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = request.getHeader("X-Internal-Token");
        if (token != null && token.equals(internalToken)) {
            return true;
        }
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.of(ErrorCode.UNAUTHORIZED, "internal token 无效", null)));
        return false;
    }
}
