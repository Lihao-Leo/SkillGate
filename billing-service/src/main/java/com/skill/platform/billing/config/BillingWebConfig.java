package com.skill.platform.billing.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：/internal/** 服务间接口启用静态 token 认证（V1；演进 mTLS + IP 白名单）。
 */
@Configuration
@RequiredArgsConstructor
public class BillingWebConfig implements WebMvcConfigurer {

    private final InternalAuthInterceptor internalAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalAuthInterceptor).addPathPatterns("/internal/**");
    }
}
