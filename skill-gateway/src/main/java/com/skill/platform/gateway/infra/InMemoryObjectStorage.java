package com.skill.platform.gateway.infra;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内对象存储（local profile / 测试）：预签名 URL 为确定性伪造值，供链路联调。
 */
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

    @Override
    public PresignedUrl presignPut(String key, Duration ttl) {
        return new PresignedUrl("https://oss.local/" + key + "?presign=put", LocalDateTime.now().plus(ttl));
    }

    @Override
    public PresignedUrl presignGet(String key, Duration ttl) {
        return new PresignedUrl("https://oss.local/" + key + "?presign=get&Expires=24h",
                LocalDateTime.now().plus(ttl));
    }

    @Override
    public byte[] get(String key) {
        return objects.get(key);
    }

    @Override
    public String publicUrl(String key) {
        return "https://oss.local/" + key;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes);
        contentTypes.put(key, contentType);
    }

    @Override
    public ObjectStat stat(String key) {
        byte[] bytes = objects.get(key);
        return bytes == null ? null : new ObjectStat(bytes.length, contentTypes.get(key));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        contentTypes.remove(key);
    }
}
