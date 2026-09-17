package com.skill.platform.gateway.infra;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * S3 兼容对象存储实现（dev/prod profile）：全私有桶，读写均走预签名或服务端直写。
 *
 * <p>桶内统一前缀：物理 key = keyPrefix + 逻辑 key（逻辑 key 落库不加前缀，
 * 与 worker 侧 S3Oss 约定一致，两侧 prefix 必须相同）。
 */
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String keyPrefix;
    /** 虚拟主机式绝对基址（https://bucket.endpoint），publicUrl 由此拼接 */
    private final String baseUrl;

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    @Override
    public PresignedUrl presignPut(String key, Duration ttl) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket).key(fullKey(key)).build();
        String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(request)
                        .build())
                .url().toString();
        return new PresignedUrl(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    public PresignedUrl presignGet(String key, Duration ttl) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket).key(fullKey(key)).build();
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(request)
                        .build())
                .url().toString();
        return new PresignedUrl(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(fullKey(key))
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(fullKey(key))).readAllBytes();
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException | java.io.IOException e) {
            return null;
        }
    }

    @Override
    public String publicUrl(String key) {
        return baseUrl + "/" + fullKey(key);
    }

    @Override
    public ObjectStat stat(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(fullKey(key)).build());
            return new ObjectStat(head.contentLength(), head.contentType());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(fullKey(key)).build());
    }
}
