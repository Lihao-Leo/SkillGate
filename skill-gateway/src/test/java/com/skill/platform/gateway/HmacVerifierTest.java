package com.skill.platform.gateway;

import com.skill.platform.gateway.security.HmacVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HMAC 请求签名测试：签名可复算、时间戳容差、错误密钥/篡改 body 拒绝。
 */
class HmacVerifierTest {

    private static final String SECRET = "sk-secret-test";
    private static final String APP_KEY = "sk-test-key";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void signature_is_deterministic_and_verifiable() {
        byte[] body = "{\"skillCode\":\"demo\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacVerifier.signature(SECRET, APP_KEY, NOW, "POST", "/api/v1/execute", body);

        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "POST", "/api/v1/execute", body, NOW)).isTrue();
    }

    @Test
    void signature_covers_path_method_and_body() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacVerifier.signature(SECRET, APP_KEY, NOW, "POST", "/api/v1/execute", body);

        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "GET", "/api/v1/execute", body, NOW)).isFalse();
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "POST", "/api/v1/execute2", body, NOW)).isFalse();
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "POST", "/api/v1/execute", "tampered".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
    }

    @Test
    void wrong_secret_rejected() {
        byte[] body = new byte[0];
        String signature = HmacVerifier.signature(SECRET, APP_KEY, NOW, "GET", "/api/v1/executions/t1", body);
        assertThat(HmacVerifier.verify("sk-secret-other", APP_KEY, String.valueOf(NOW), signature,
                "GET", "/api/v1/executions/t1", body, NOW)).isFalse();
    }

    @Test
    void timestamp_skew_beyond_five_minutes_rejected() {
        byte[] body = new byte[0];
        String signature = HmacVerifier.signature(SECRET, APP_KEY, NOW, "GET", "/api/v1/executions/t1", body);
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "GET", "/api/v1/executions/t1", body, NOW + 5 * 60_000L + 1)).isFalse();
        // 恰好 5min 内放行
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, String.valueOf(NOW), signature,
                "GET", "/api/v1/executions/t1", body, NOW + 5 * 60_000L)).isTrue();
    }

    @Test
    void malformed_inputs_rejected() {
        byte[] body = new byte[0];
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, "not-a-number", "deadbeef",
                "GET", "/p", body, NOW)).isFalse();
        assertThat(HmacVerifier.verify(SECRET, APP_KEY, null, null, "GET", "/p", body, NOW)).isFalse();
    }
}
