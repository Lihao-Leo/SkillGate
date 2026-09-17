package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单（技术方案 v6.9 §10.4 recharge_order 表，DDL 之上追加重试推进列：
 * retry_count / next_retry_at / last_error——退避重试需要自身状态，只加列向前兼容）。
 */
@Data
@TableName("recharge_order")
public class RechargeOrder {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CREDITED = "CREDITED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台订单号（R+时间戳+序号），全局唯一，入账幂等键 */
    private String orderNo;

    /** 充值平台用户 */
    private Long userId;

    /** WECHAT / ALIPAY / MOCK */
    private String channel;

    /** 购买点数（含赠送） */
    private Integer skuPoints;

    /** 应付金额（分）——内部一律分，杜绝浮点 */
    private Long amountFen;

    /** CREATED/PAYING/PAID/CREDITED/EXPIRED */
    private String status;

    /** 渠道二维码链接 / 跳转 URL */
    private String codeUrl;

    /** 渠道支付流水号（回调回填） */
    private String tradeNo;

    /** 实付金额（回调校验，须 == amount_fen） */
    private Long paidAmountFen;

    /** 二维码有效期（15min） */
    private LocalDateTime expireAt;

    private LocalDateTime paidAt;

    /** 入账+签发完成时间 */
    private LocalDateTime creditedAt;

    private LocalDateTime createdAt;

    // ---- 重试任务推进（v6.9：外调失败 1/5/15min 退避，超 1h 告警人工） ----
    /** 入账外调重试次数 */
    private Integer retryCount;

    /** 下次重试时间（退避后） */
    private LocalDateTime nextRetryAt;

    /** 最近一次外调失败原因 */
    private String lastError;
}
