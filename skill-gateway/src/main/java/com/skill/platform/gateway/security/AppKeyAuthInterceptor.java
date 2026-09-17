package com.skill.platform.gateway.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.dal.mapper.AppKeyMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AppKey HMAC 请求签名鉴权（受理五关第①关）：
 * AppKey 有效（启用）+ 时间戳容差 5min + HMAC-SHA256 签名匹配，失败 40101。
 */
@Component
@RequiredArgsConstructor
public class AppKeyAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_CALLER = "gateway.caller";

    private final AppKeyMapper appKeyMapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String appKeyId = request.getHeader(HmacVerifier.HEADER_APP_KEY);
        String timestamp = request.getHeader(HmacVerifier.HEADER_TIMESTAMP);
        String signature = request.getHeader(HmacVerifier.HEADER_SIGNATURE);

        if (appKeyId == null || timestamp == null || signature == null) {
            return reject(response, "缺少鉴权 Header");
        }
        AppKey appKey = appKeyMapper.selectOne(new LambdaQueryWrapper<AppKey>()
                .eq(AppKey::getAppKeyId, appKeyId));
        if (appKey == null || appKey.getStatus() != 1) {
            return reject(response, "AppKey 无效或已禁用");
        }
        String pathWithQuery = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        byte[] body = (byte[]) request.getAttribute(CachedBodyFilter.ATTR_CACHED_BODY);
        boolean verified = HmacVerifier.verify(cryptoService.decrypt(appKey.getSecretCipher()),
                appKeyId, timestamp, signature, request.getMethod(), pathWithQuery, body,
                System.currentTimeMillis());
        if (!verified) {
            return reject(response, "请求签名错误或时间戳超窗");
        }
        request.setAttribute(ATTR_CALLER, new CallerContext(appKeyId, appKey.getTenantId()));
        return true;
    }

    private boolean reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.of(ErrorCode.UNAUTHORIZED, message, null)));
        return false;
    }
}
