package com.skill.platform.billing.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费冻结单（技术方案 v6.5 §6.9）：一任务一冻结单；对账任务扫 FROZEN 超 24h。
 */
@Data
@TableName("billing_hold")
public class BillingHold {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_RELEASED = "RELEASED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 冻结单ID */
    private String holdId;

    /** 关联执行任务（uk） */
    private String taskId;

    /** 扣点账户 */
    private String appKeyId;

    /** 冻结点数（预估：单价×count 或 capPoints） */
    private Long amount;

    /** FROZEN / SETTLED / RELEASED */
    private String status;

    /** 实际结算点数（≤amount，差额已解冻） */
    private Long settledAmount;

    private LocalDateTime createdAt;

    /** 结算/释放时间 */
    private LocalDateTime settledAt;
}
