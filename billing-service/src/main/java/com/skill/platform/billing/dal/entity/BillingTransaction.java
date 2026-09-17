package com.skill.platform.billing.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费流水（技术方案 v6.5 §6.10）：不可变（只插入不更新），审计与对账依据。
 */
@Data
@TableName("billing_transaction")
public class BillingTransaction {

    public static final String TYPE_HOLD = "HOLD";
    public static final String TYPE_SETTLE = "SETTLE";
    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_ADJUST = "ADJUST";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流水ID */
    private String txId;

    /** 账户 */
    private String appKeyId;

    /** 关联任务（HOLD/SETTLE/RELEASE 带；充值/调整为 NULL） */
    private String taskId;

    /** HOLD / SETTLE / RELEASE / RECHARGE / ADJUST */
    private String type;

    /** 点数变动（正/负） */
    private Long amount;

    /** 变动后可用余额快照（审计） */
    private Long balanceAfter;

    /** 备注（退款原因等） */
    private String remark;

    /** 充值订单号（RECHARGE 幂等键：与 type 组成唯一约束；其余类型 NULL） */
    private String orderNo;

    private LocalDateTime createdAt;
}
