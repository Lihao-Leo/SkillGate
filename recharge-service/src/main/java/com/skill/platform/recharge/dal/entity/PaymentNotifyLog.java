package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付回调留档（§10.4：原始报文全量落库，审计 + 排障回放；结构从略由本服务定义）。
 */
@Data
@TableName("payment_notify_log")
public class PaymentNotifyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String notifyId;

    /** WECHAT / ALIPAY / MOCK */
    private String channel;

    /** 解析出的订单号（无法解析为 NULL） */
    private String orderNo;

    /** 原始报文全量留档 */
    private String rawBody;

    /** 验签是否通过（金额不符等业务失败验签仍为 1，见 verify_message） */
    private Integer signatureValid;

    /** 校验结论描述（ok / 验签失败 / 金额不符 / 幂等重复） */
    private String verifyMessage;

    /** 报文金额（分，可解析时） */
    private Long amountFen;

    /** 是否已推进订单状态（1=已处理；0=仅留档） */
    private Integer processed;

    private LocalDateTime createdAt;
}
