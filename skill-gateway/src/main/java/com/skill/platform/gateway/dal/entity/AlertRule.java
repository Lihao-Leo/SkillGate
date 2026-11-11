package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则（V2.1）：worker 经 /internal/runtime-config/alerts 60s 热下发，
 * 触发判定在 worker（持有时效性运行时数据），gateway 负责规则管理与告警落库。
 */
@Data
@TableName("alert_rule")
public class AlertRule {

    /** RUNNING_TIMEOUT=运行超时 TOKEN_EXCESS=token 超量 TASK_FAILED=任务失败
     *  PENDING_STUCK=排队积压 CALLBACK_DEAD_LETTER=回调死信 WATCHDOG_INTERVENTION=看门狗干预 */
    public static final String TYPE_RUNNING_TIMEOUT = "RUNNING_TIMEOUT";
    public static final String TYPE_TOKEN_EXCESS = "TOKEN_EXCESS";
    public static final String TYPE_TASK_FAILED = "TASK_FAILED";
    public static final String TYPE_PENDING_STUCK = "PENDING_STUCK";
    public static final String TYPE_CALLBACK_DEAD_LETTER = "CALLBACK_DEAD_LETTER";
    public static final String TYPE_WATCHDOG_INTERVENTION = "WATCHDOG_INTERVENTION";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则编码（告警去重键组成部分） */
    private String ruleCode;

    private String ruleName;

    private String ruleType;

    /** 限定 Skill 编码；NULL=全部 Skill */
    private String skillCode;

    /** 阈值：秒（超时类）/ token 数（超量类） */
    private Long thresholdValue;

    /** INFO / WARN / CRITICAL */
    private String severity;

    /** 通知通道：feishu=飞书 webhook record=仅落库（逗号分隔） */
    private String channels;

    /** 同任务同规则去重窗口（秒） */
    private Integer dedupWindowSeconds;

    /** 1=启用 0=停用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
