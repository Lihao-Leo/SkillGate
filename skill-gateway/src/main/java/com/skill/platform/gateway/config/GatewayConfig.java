package com.skill.platform.gateway.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.skill.platform.gateway.security.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用 Bean 配置：时钟（限流窗口可测试）、MyBatis-Plus 分页、凭据加密服务。
 */
@Configuration
public class GatewayConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * AppSecret / 供应商 Key 加密密钥（base64 32B）。生产经 K8s Secret 注入。
     */
    @Bean
    public CryptoService cryptoService(
            @Value("${skill-platform.crypto-key}") String base64Key) {
        return new CryptoService(base64Key);
    }
}
