package com.skill.platform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * HMAC-SHA256 请求签名（技术方案 v6.5 §2/§5.5 对称密钥体系：AppSecret 同时用于
 * 请求签名与回调签名）。
 *
 * <p>签名串（五段，\n 分隔）：
 * {@code appKey + "\n" + timestamp + "\n" + method(大写) + "\n" + path[?query] + "\n" + sha256Hex(body)}
 *
 * <ul>
 *   <li>GET/DELETE 等无 body 请求：body 取空字节串</li>
 *   <li>时间戳容差 ±5min 防重放（与回调一致）</li>
 * </ul>
 */
public class HmacVerifier {

    public static final String HEADER_APP_KEY = "X-Skill-AppKey";
    public static final String HEADER_TIMESTAMP = "X-Skill-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Skill-Signature";

    private static final long CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;

    /**
     * 计算签名（测试与调用方 SDK 实现同源）。
     */
    public static String signature(String secret, String appKey, long timestamp,
                                   String method, String pathWithQuery, byte[] body) {
        String stringToSign = String.join("\n",
                appKey,
                String.valueOf(timestamp),
                method.toUpperCase(),
                pathWithQuery,
                sha256Hex(body == null ? new byte[0] : body));
        return hmacSha256(secret, stringToSign);
    }

    /**
     * 验签：时间戳容差校验 + 常量时间比较。
     */
    public static boolean verify(String secret, String appKey, String timestampText, String signature,
                                 String method, String pathWithQuery, byte[] body, long nowMillis) {
        if (secret == null || timestampText == null || signature == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMillis - timestamp) > CLOCK_SKEW_MILLIS) {
            return false;
        }
        String expected = signature(secret, appKey, timestamp, method, pathWithQuery, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
