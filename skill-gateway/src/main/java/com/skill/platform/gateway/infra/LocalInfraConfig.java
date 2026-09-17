package com.skill.platform.gateway.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local profile（零依赖测试/联调）Bean：进程内 KV 存储。
 * dev/prod 为 RedisKvStore（@Profile({"dev","prod"})）。
 */
@Configuration
public class LocalInfraConfig {

    @Bean
    @Profile("local")
    public KvStore kvStore() {
        return new InMemoryKvStore();
    }
}
