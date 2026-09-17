package com.skill.platform.gateway.infra;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * KV 存储端口：限流计数（incr + TTL）、轮询终态缓存（TTL 2s）、进度透传统一出口。
 * 本地为进程内实现；生产为 Redis（§3.5）。
 */
public interface KvStore {

    String get(String key);

    void set(String key, String value, Duration ttl);

    /** 自增并返回新值；键首次创建时设置 TTL */
    long incr(String key, Duration ttl);

    /** 自减，下限 0（租户并发计数释放用） */
    long decrFloorZero(String key);
}
