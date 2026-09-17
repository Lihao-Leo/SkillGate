package com.skill.platform.recharge.channel;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 渠道加解密原语：RSA-SHA256 请求签名/验签（微信 APIv3 / 支付宝 RSA2）、AES-256-GCM 回调解密。
 */
public final class ChannelCrypto {

    private ChannelCrypto() {
    }

    /** RSA-SHA256 签名（私钥 PKCS#8 base64） */
    public static String rsaSign(String privateKeyBase64, String message) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("rsa sign failed", e);
        }
    }

    /** RSA-SHA256 验签（公钥 X509 base64） */
    public static boolean rsaVerify(String publicKeyBase64, String message, String signBase64) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 微信 APIv3 回调资源解密：AES-256-GCM，密文 = base64(apiV3Key) + nonce + associatedData。
     */
    public static String aesGcmDecrypt(String apiV3Key, String ciphertextBase64,
                                       String nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("wechat resource decrypt failed", e);
        }
    }

    public static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
