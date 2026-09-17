package com.skill.platform.recharge.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.skill.platform.recharge.security.CryptoService;
import com.skill.platform.recharge.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用配置：分页插件、待展示密钥加密、JWT 过滤器注册（仅 /api/user/**）。
 */
@Configuration
@RequiredArgsConstructor
public class RechargeConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public CryptoService cryptoService(
            @Value("${recharge.crypto-key}") String base64Key) {
        return new CryptoService(base64Key);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration() {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.addUrlPatterns("/api/user/*");
        registration.setOrder(10);
        return registration;
    }
}
