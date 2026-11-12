package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消耗预算（月度）：按 scope（全局/租户/Skill）设额度；达阈值百分比产生告警记录（不拦截执行）。
 */
@Data
@TableName("budget")
public class Budget {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_SKILL = "SKILL";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** GLOBAL / TENANT / SKILL */
    private String scopeType;

    /** GLOBAL 为空串；TENANT=租户ID；SKILL=skillCode */
    private String scopeValue;

    /** 周期：MONTHLY（v1 仅月度） */
    private String periodType;

    /** 周期额度（点） */
    private Long amountPoints;

    /** 告警阈值百分比 */
    private Integer alertThresholdPct;

    /** 1=启用 0=停用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
