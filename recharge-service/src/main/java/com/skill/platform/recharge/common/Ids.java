package com.skill.platform.recharge.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务 ID 生成：订单号 R+时间戳+序号（方案 §10.4 recharge_order.order_no 约定），全局唯一。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong SEQ = new AtomicLong();

    private Ids() {
    }

    /** 平台订单号：R + 时间戳 + 3 位日内序列 + 4 位随机（防跨实例碰撞） */
    public static String nextOrderNo() {
        byte[] rand = new byte[2];
        RANDOM.nextBytes(rand);
        return "R" + TS.format(LocalDateTime.now())
                + String.format("%03d", SEQ.incrementAndGet() % 1000)
                + HexFormat.of().formatHex(rand);
    }

    public static String next(String prefix) {
        byte[] rand = new byte[4];
        RANDOM.nextBytes(rand);
        return prefix + TS.format(LocalDateTime.now()) + "_" + HexFormat.of().formatHex(rand);
    }

    /** 6 位数字验证码 */
    public static String verificationCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
