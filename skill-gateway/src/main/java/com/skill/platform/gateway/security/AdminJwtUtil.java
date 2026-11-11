package com.skill.platform.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 管理后台 JWT（HS256）：与 recharge-service JwtUtil 同思路的手写最简实现
 * （仓库零 JWT 依赖惯例），claims 序列化经 Jackson 以承载 role。
 *
 * <p>claims：{@code {sub:username, role:roleCode, exp:epochSeconds}}。
 */
public final class AdminJwtUtil {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdminJwtUtil() {
    }

    /** 令牌载体：验签通过后的声明集。 */
    public record AdminClaims(String username, String role, long exp) {
    }

    public static String issue(String secret, String username, String role, long expireHours) {
        long exp = Instant.now().getEpochSecond() + expireHours * 3600;
        String payload;
        try {
            payload = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("sub", username)
                    .put("role", role)
                    .put("exp", exp));
        } catch (Exception e) {
            throw new IllegalStateException("jwt payload serialize failed", e);
        }
        String header = URL_ENCODER.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String encodedPayload = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + encodedPayload;
        return signingInput + "." + hmac(secret, signingInput);
    }

    /** 验签 + 过期校验；有效返回声明集，否则 null。 */
    public static AdminClaims verify(String secret, String token) {
        if (token == null || token.isBlank()) {
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
        try {
            JsonNode claims = MAPPER.readTree(URL_DECODER.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (Instant.now().getEpochSecond() >= exp) {
                return null;
            }
            String username = claims.path("sub").asText(null);
            String role = claims.path("role").asText(null);
            if (username == null || role == null) {
                return null;
            }
            return new AdminClaims(username, role, exp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("jwt hmac failed", e);
        }
    }
}
