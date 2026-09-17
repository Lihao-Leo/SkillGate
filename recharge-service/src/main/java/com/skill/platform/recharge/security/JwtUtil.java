package com.skill.platform.recharge.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * 最简 JWT（HS256）：充值用户端会话。声明仅 sub（userId）与 exp，避免引重型依赖。
 */
public final class JwtUtil {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private JwtUtil() {
    }

    public static String issue(String secret, long userId, long expiresAtEpochSeconds) {
        String header = URL_ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = URL_ENCODER.encodeToString(
                ("{\"sub\":\"" + userId + "\",\"exp\":" + expiresAtEpochSeconds + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + hmac(secret, signingInput);
    }

    /** 验签 + 过期校验；有效返回 userId，否则 null */
    public static Long verify(String secret, String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String expected = hmac(secret, parts[0] + "." + parts[1]);
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String payload = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        int subStart = payload.indexOf("\"sub\":\"");
        int expStart = payload.indexOf("\"exp\":");
        if (subStart < 0 || expStart < 0) {
            return null;
        }
        long exp = Long.parseLong(payload.substring(expStart + 6, payload.indexOf('}', expStart)));
        if (Instant.now().getEpochSecond() >= exp) {
            return null;
        }
        int subValueStart = subStart + 7;
        return Long.parseLong(payload.substring(subValueStart, payload.indexOf('"', subValueStart)));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
