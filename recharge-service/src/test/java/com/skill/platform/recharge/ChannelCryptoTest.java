package com.skill.platform.recharge;

import com.skill.platform.recharge.channel.ChannelCrypto;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道加解密原语测试：RSA-SHA256 签名验签（微信 APIv3 / 支付宝 RSA2 同原语）、
 * AES-256-GCM 回调资源解密（微信 APIv3 resource 格式）。
 */
class ChannelCryptoTest {

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void rsa_sign_verify_roundtrip_and_tamper_detection() throws Exception {
        KeyPair pair = rsaKeyPair();
        String privateKey = base64(pair.getPrivate().getEncoded());
        String publicKey = base64(pair.getPublic().getEncoded());
        String message = "POST\n/v3/pay/transactions/native\n1700000000\nnonce\n{\"a\":1}\n";

        String sign = ChannelCrypto.rsaSign(privateKey, message);
        assertThat(ChannelCrypto.rsaVerify(publicKey, message, sign)).isTrue();
        // 篡改报文 / 错误公钥 → 验签失败
        assertThat(ChannelCrypto.rsaVerify(publicKey, message + "x", sign)).isFalse();
        KeyPair other = rsaKeyPair();
        assertThat(ChannelCrypto.rsaVerify(base64(other.getPublic().getEncoded()), message, sign))
                .isFalse();
        // 非法输入不抛异常
        assertThat(ChannelCrypto.rsaVerify("not-base64!!", message, sign)).isFalse();
    }

    @Test
    void wechat_aes_gcm_resource_decrypt_roundtrip() throws Exception {
        // 按微信 APIv3 resource 格式构造密文：AES-256-GCM(key=apiV3Key, nonce, aad)
        String apiV3Key = "0123456789abcdef0123456789abcdef";
        String nonce = "nonce123";
        String aad = "transaction";
        String plain = "{\"out_trade_no\":\"R20260914x\",\"amount\":{\"total\":1000}}";

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String ciphertext = base64(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));

        assertThat(ChannelCrypto.aesGcmDecrypt(apiV3Key, ciphertext, nonce, aad)).isEqualTo(plain);
        // aad 不符 → 解密失败
        boolean failed = false;
        try {
            ChannelCrypto.aesGcmDecrypt(apiV3Key, ciphertext, nonce, "other");
        } catch (Exception expected) {
            failed = true;
        }
        assertThat(failed).isTrue();
    }

    @Test
    void hmac_is_deterministic() {
        assertThat(ChannelCrypto.hmacSha256("secret", "data"))
                .isEqualTo(ChannelCrypto.hmacSha256("secret", "data"))
                .hasSize(64)
                .isNotEqualTo(ChannelCrypto.hmacSha256("secret2", "data"));
    }
}
