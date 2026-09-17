package com.skill.platform.gateway.infra;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 对象存储端口：全私有桶，对外一律预签名（§2 原则 5：OSS 凭据不出平台）。
 */
public interface ObjectStorage {

    /** 预签名 PUT（素材 presign 直传，15min，单链接） */
    PresignedUrl presignPut(String key, Duration ttl);

    /** 预签名 GET（产物 24h / 分发包下载） */
    PresignedUrl presignGet(String key, Duration ttl);

    void put(String key, byte[] bytes, String contentType);

    /** 读取对象内容；不存在返回 null（服务端内部使用，如 KB 文档入库） */
    byte[] get(String key);

    /** 对象绝对访问地址（bucket endpoint + key 的稳定指针；实际读写仍一律预签名） */
    String publicUrl(String key);

    /** 对象元信息；不存在返回 null */
    ObjectStat stat(String key);

    void delete(String key);

    record PresignedUrl(String url, LocalDateTime expiresAt) {
    }

    record ObjectStat(long size, String contentType) {
    }
}
