package com.skill.platform.gateway.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桶内统一前缀测试：物理 key = prefix + 逻辑 key。
 * 预签名是纯本地计算（不联网），用真实 S3Presigner 断言 URL 内嵌物理 key。
 */
class S3ObjectStorageTest {

    private static S3ObjectStorage storage(String keyPrefix) {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test-ak", "test-sk"));
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create("https://cos.ap-guangzhou.myqcloud.com"))
                .region(Region.of("ap-guangzhou"))
                .credentialsProvider(creds)
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create("https://cos.ap-guangzhou.myqcloud.com"))
                .region(Region.of("ap-guangzhou"))
                .credentialsProvider(creds)
                .build();
        return new S3ObjectStorage(s3, presigner, "test-bucket-1234567890", keyPrefix,
                "https://test-bucket-1234567890.cos.ap-guangzhou.myqcloud.com");
    }

    @Test
    void presignedUrlsEmbedKeyPrefix() {
        S3ObjectStorage prefixed = storage("skill-platform/");
        String putUrl = prefixed.presignPut("t1/materials/m1.mp4", Duration.ofMinutes(15)).url();
        String getUrl = prefixed.presignGet("t1/artifacts/x/out.mp4", Duration.ofHours(24)).url();

        assertThat(putUrl)
                .contains("test-bucket-1234567890.cos.ap-guangzhou.myqcloud.com")
                .contains("/skill-platform/t1/materials/m1.mp4");
        assertThat(getUrl).contains("/skill-platform/t1/artifacts/x/out.mp4");
    }

    @Test
    void emptyPrefixKeepsLogicalKey() {
        String getUrl = storage("").presignGet("k.txt", Duration.ofMinutes(1)).url();
        assertThat(getUrl).contains("/k.txt");
    }
}
