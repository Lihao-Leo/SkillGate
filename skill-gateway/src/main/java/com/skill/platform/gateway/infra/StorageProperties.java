package com.skill.platform.gateway.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * 对象存储配置（S3 兼容双供应商）：阿里云 OSS / 腾讯云 COS 字段同构，
 * {@code skill-platform.storage.provider} 决定生效哪个供应商的配置块。
 *
 * <pre>
 * skill-platform:
 *   storage:
 *     provider: oss          # oss=skill-platform.storage.oss / cos=skill-platform.storage.cos
 *     key-prefix: skill-platform/   # 所有对象统一落在桶内该目录下（两侧服务需一致）
 *     oss:  { endpoint, region, bucket, access-key, secret-key }
 *     cos:  { endpoint, region, bucket, access-key, secret-key }
 * </pre>
 */
@ConfigurationProperties(prefix = "skill-platform.storage")
public record StorageProperties(
        @DefaultValue("OSS") Provider provider,
        @DefaultValue("skill-platform/") String keyPrefix,
        Endpoint oss,
        Endpoint cos) {

    public enum Provider { OSS, COS }

    /**
     * 归一化桶内前缀：非空时保证尾斜杠；空串表示不加前缀（落桶根）。
     */
    public String normalizedKeyPrefix() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return "";
        }
        return keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
    }

    /**
     * 单供应商连接配置：两者同为 S3 兼容端点，字段一致。
     */
    public record Endpoint(String endpoint, String region, String bucket, String accessKey, String secretKey) {

        boolean complete() {
            return notBlank(endpoint) && notBlank(region) && notBlank(bucket)
                    && notBlank(accessKey) && notBlank(secretKey);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        /**
         * 允许省略 scheme（默认 https），生成 SDK endpointOverride 所需 URI。
         */
        public URI uri() {
            String url = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                    ? endpoint : "https://" + endpoint;
            return URI.create(url);
        }
    }

    /**
     * 当前生效供应商的连接配置；所选块未配置完整时快速失败（启动期暴露而非运行期 403）。
     */
    public Endpoint active() {
        Provider p = provider == null ? Provider.OSS : provider;
        Endpoint selected = p == Provider.COS ? cos : oss;
        if (selected == null || !selected.complete()) {
            throw new IllegalStateException(("storage provider=%s 已启用，但 skill-platform.storage.%s "
                    + "未配置完整（需 endpoint/region/bucket/access-key/secret-key）")
                    .formatted(p, p.name().toLowerCase()));
        }
        return selected;
    }
}
