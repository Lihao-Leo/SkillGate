package com.skill.platform.gateway;

import com.skill.platform.gateway.security.CryptoService;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据加密测试：AES-GCM 往返、随机 IV、密钥长度校验。
 */
class CryptoServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encrypt_decrypt_roundtrip() {
        CryptoService crypto = new CryptoService(KEY);
        String secret = "sk-secret-abcdef123456";
        String cipher = crypto.encrypt(secret);

        assertThat(cipher).isNotEqualTo(secret);
        assertThat(crypto.decrypt(cipher)).isEqualTo(secret);
    }

    @Test
    void random_iv_same_plain_different_cipher() {
        CryptoService crypto = new CryptoService(KEY);
        String secret = "sk-secret-same";
        assertThat(crypto.encrypt(secret)).isNotEqualTo(crypto.encrypt(secret));
        assertThat(crypto.decrypt(crypto.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void invalid_key_size_rejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(shortKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tampered_cipher_fails() {
        CryptoService crypto = new CryptoService(KEY);
        String cipher = crypto.encrypt("sk-secret-x");
        byte[] bytes = Base64.getDecoder().decode(cipher);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
