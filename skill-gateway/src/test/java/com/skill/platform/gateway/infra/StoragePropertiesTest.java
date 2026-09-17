package com.skill.platform.gateway.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象存储双供应商配置测试：provider 选择、未配置完整快速失败、endpoint scheme 归一、
 * 桶内统一前缀归一。
 */
class StoragePropertiesTest {

    private static StorageProperties.Endpoint endpoint(String endpoint, String region) {
        return new StorageProperties.Endpoint(endpoint, region,
                "skill-platform", "ak", "sk");
    }

    @Test
    void providerDefaultsToOss() {
        StorageProperties props = new StorageProperties(
                null, "skill-platform/",
                endpoint("https://oss-cn-shenzhen.aliyuncs.com", "cn-shenzhen"), null);
        assertThat(props.active().endpoint()).isEqualTo("https://oss-cn-shenzhen.aliyuncs.com");
    }

    @Test
    void cosProviderSelectsCosBlock() {
        StorageProperties props = new StorageProperties(
                StorageProperties.Provider.COS, "skill-platform/",
                endpoint("https://oss-cn-shenzhen.aliyuncs.com", "cn-shenzhen"),
                endpoint("https://cos.ap-guangzhou.myqcloud.com", "ap-guangzhou"));
        assertThat(props.active().endpoint()).isEqualTo("https://cos.ap-guangzhou.myqcloud.com");
    }

    @Test
    void incompleteSelectedBlockFailsFast() {
        StorageProperties cosBlank = new StorageProperties(
                StorageProperties.Provider.COS, "skill-platform/",
                endpoint("https://oss-cn-shenzhen.aliyuncs.com", "cn-shenzhen"),
                new StorageProperties.Endpoint("https://cos.ap-guangzhou.myqcloud.com",
                        "ap-guangzhou", "skill-platform-1250000000", "", ""));
        assertThatThrownBy(cosBlank::active)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill-platform.storage.cos");

        StorageProperties ossMissing = new StorageProperties(
                StorageProperties.Provider.OSS, "skill-platform/", null, null);
        assertThatThrownBy(ossMissing::active)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill-platform.storage.oss");
    }

    @Test
    void uriNormalizesMissingSchemeToHttps() {
        assertThat(endpoint("cos.ap-guangzhou.myqcloud.com", "ap-guangzhou").uri())
                .isEqualTo(java.net.URI.create("https://cos.ap-guangzhou.myqcloud.com"));
        assertThat(endpoint("https://cos.ap-guangzhou.myqcloud.com", "ap-guangzhou").uri())
                .isEqualTo(java.net.URI.create("https://cos.ap-guangzhou.myqcloud.com"));
    }

    @Test
    void keyPrefixNormalizedWithTrailingSlash() {
        assertThat(new StorageProperties(null, "skill-platform/", null, null).normalizedKeyPrefix())
                .isEqualTo("skill-platform/");
        assertThat(new StorageProperties(null, "skill-platform", null, null).normalizedKeyPrefix())
                .isEqualTo("skill-platform/");
        assertThat(new StorageProperties(null, "", null, null).normalizedKeyPrefix())
                .isEmpty();
        assertThat(new StorageProperties(null, null, null, null).normalizedKeyPrefix())
                .isEmpty();
    }
}
