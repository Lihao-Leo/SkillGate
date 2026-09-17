package com.skill.platform.gateway.config;

import com.skill.platform.gateway.security.AdminAuthInterceptor;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册：
 * <ul>
 *   <li>/api/v1/** 默认 AppKey HMAC 鉴权（管理 / 埋点子路径除外）</li>
 *   <li>/api/v1/admin/** 独立 admin token（互斥路径，先注册）</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppKeyAuthInterceptor appKeyAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(appKeyAuthInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/admin/**", "/api/v1/telemetry/**");
    }
}
