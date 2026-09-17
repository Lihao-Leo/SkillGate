package com.skill.platform.recharge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.recharge.common.ApiResponse;
import com.skill.platform.recharge.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 用户会话过滤器：/api/user/**（auth 除外）要求 Bearer JWT。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "recharge.user";

    // 套餐为只读营销数据，登录前可浏览（UserAuthController.skus 注释同源）
    private static final Set<String> WHITELIST = Set.of(
            "/api/user/auth/send-code", "/api/user/auth/login", "/api/user/auth/skus");

    private final ObjectMapper objectMapper;

    @Value("${recharge.jwt.secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (WHITELIST.contains(path) || !path.startsWith("/api/user/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        Long userId = authorization != null && authorization.startsWith("Bearer ")
                ? JwtUtil.verify(jwtSecret, authorization.substring(7))
                : null;
        if (userId == null) {
            response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.of(ErrorCode.UNAUTHORIZED, "未登录或会话已过期", null)));
            return;
        }
        String identifier = request.getHeader("X-User-Identifier");
        request.setAttribute(ATTR_USER, new UserContext(userId, identifier));
        filterChain.doFilter(request, response);
    }
}
