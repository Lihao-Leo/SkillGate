package com.skill.platform.billing.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 业务 ID 生成：前缀 + 时间戳 + 随机段（hold_ / tx_），可读可排序，碰撞概率可忽略。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Ids() {
    }

    public static String next(String prefix) {
        byte[] rand = new byte[4];
        RANDOM.nextBytes(rand);
        return prefix + TS.format(LocalDateTime.now()) + "_" + HexFormat.of().formatHex(rand);
    }
}
