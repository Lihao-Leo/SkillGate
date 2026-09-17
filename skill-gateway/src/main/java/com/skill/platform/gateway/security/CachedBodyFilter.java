package com.skill.platform.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 请求体缓存过滤器：非 multipart 的 JSON 请求读取原始 body 缓存为 request attribute，
 * 供 HMAC 验签计算 body 摘要后，Controller 仍可正常反序列化。
 *
 * <p>multipart 大文件不缓存（内存不可控）：签名口径为 sha256(空 body)，方法与路径仍签名
 * （与 S3 预签名 PUT 不签内容的行业实践一致，传输安全由 TLS 保证）。
 */
@Component
public class CachedBodyFilter extends OncePerRequestFilter {

    public static final String ATTR_CACHED_BODY = "gateway.cachedBody";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean withBody = request.getContentLengthLong() > 0
                && !isMultipart(request.getContentType());
        if (!withBody) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        request.setAttribute(ATTR_CACHED_BODY, body);
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取无需异步监听
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }
    }
}
