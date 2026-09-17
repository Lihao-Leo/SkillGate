package com.skill.platform.gateway.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local profile OSS Bean：进程内实现（零外部依赖自洽）；
 * dev/prod 均装配真实存储（S3Config，COS/OSS）。
 */
@Configuration
public class LocalStorageConfig {

    @Bean
    @Profile("local")
    public ObjectStorage objectStorage() {
        return new InMemoryObjectStorage();
    }
}
