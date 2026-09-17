package com.skill.platform.gateway.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis KV 实现（dev/prod profile）：限流/缓存/进度统一出口（§3.5）。
 */
@Component
@Profile({"dev", "prod"})
@RequiredArgsConstructor
public class RedisKvStore implements KvStore {

    private final StringRedisTemplate redis;

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public long incr(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redis.expire(key, ttl);
        }
        return value == null ? 0 : value;
    }

    @Override
    public long decrFloorZero(String key) {
        Long value = redis.opsForValue().decrement(key);
        if (value == null || value >= 0) {
            return value == null ? 0 : value;
        }
        // 负值钳回 0：漂移修复（对账任务会重置为 DB 真值）
        redis.opsForValue().set(key, "0");
        return 0;
    }
}
