package com.skill.platform.gateway.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 业务 ID / 密钥生成工具。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Ids() {
    }

    /** 业务 ID：前缀 + 时间戳 + 随机段（task_ / mat_ / art_ ...） */
    public static String next(String prefix) {
        byte[] rand = new byte[4];
        RANDOM.nextBytes(rand);
        return prefix + TS.format(LocalDateTime.now()) + "_" + HexFormat.of().formatHex(rand);
    }

    /** 对外 AppKey：sk- 前缀 32 hex */
    public static String newAppKey() {
        return "sk-" + randomHex(16);
    }

    /** AppSecret（明文仅签发时返回一次）：sk-secret- 前缀 */
    public static String newAppSecret() {
        return "sk-secret-" + randomHex(24);
    }

    /** 埋点令牌：skt- 前缀（FREE 公开分发 Skill） */
    public static String newTelemetryToken() {
        return "skt-" + randomHex(16);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 随机 base64（测试造 AES key 等场景复用） */
    public static String randomBase64(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }
}
