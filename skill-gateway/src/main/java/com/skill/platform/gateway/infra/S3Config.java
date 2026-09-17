package com.skill.platform.gateway.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 对象存储客户端：OSS 与 COS 均为 S3 兼容端点，共用同一 AWS SDK 装配；
 * 生效供应商由 {@link StorageProperties#active()} 按 provider 选择（配哪个生效哪个）。
 * dev 与 prod 都装配真实存储（联调期数据落真实桶）；local 为进程内实现。
 */
@Configuration
@Profile({"dev", "prod"})
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(StorageProperties storage) {
        StorageProperties.Endpoint active = storage.active();
        return S3Client.builder()
                .endpointOverride(active.uri())
                .region(Region.of(active.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(active.accessKey(), active.secretKey())))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(StorageProperties storage) {
        StorageProperties.Endpoint active = storage.active();
        return S3Presigner.builder()
                .endpointOverride(active.uri())
                .region(Region.of(active.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(active.accessKey(), active.secretKey())))
                .build();
    }

    @Bean
    public ObjectStorage objectStorage(
            S3Client s3Client,
            S3Presigner presigner,
            StorageProperties storage) {
        StorageProperties.Endpoint active = storage.active();
        String host = active.uri().getHost();
        String baseUrl = "https://%s.%s".formatted(active.bucket(), host);
        return new S3ObjectStorage(s3Client, presigner,
                active.bucket(), storage.normalizedKeyPrefix(), baseUrl);
    }
}
