package com.skill.platform.gateway.infra;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 KV 实现（local profile / 测试）：惰性过期，语义对齐 Redis 实现。
 */
public class InMemoryKvStore implements KvStore {

    private record Entry(String value, long expiresAtMillis, AtomicLong counter) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null || expired(entry)) {
            return null;
        }
        return entry.value();
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis(), new AtomicLong()));
    }

    @Override
    public long incr(String key, Duration ttl) {
        AtomicLong counter = store.compute(key, (k, existing) -> {
            if (existing == null || expired(existing)) {
                return new Entry(null, System.currentTimeMillis() + ttl.toMillis(), new AtomicLong());
            }
            return existing;
        }).counter();
        return counter.incrementAndGet();
    }

    @Override
    public long decrFloorZero(String key) {
        Entry entry = store.get(key);
        if (entry == null || expired(entry)) {
            return 0;
        }
        long value = entry.counter().decrementAndGet();
        return Math.max(value, 0);
    }

    private boolean expired(Entry entry) {
        return entry.expiresAtMillis() > 0 && System.currentTimeMillis() > entry.expiresAtMillis();
    }
}
