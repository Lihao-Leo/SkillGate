package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警记录（V2.1）：待处理数 = status=PENDING 计数；
 * 同任务同规则单条（uk_task_rule），重复触发刷 last_seen_at。
 */
@Data
@TableName("alert_record")
public class AlertRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACKED = "ACKED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 触发规则 id（legacy 阈值告警为 NULL） */
    private Long ruleId;

    /** 规则编码（legacy 告警写 legacy.running-timeout 等） */
    private String ruleCode;

    private String ruleType;

    /** INFO / WARN / CRITICAL */
    private String severity;

    /** PENDING / ACKED / RESOLVED */
    private String status;

    private String taskId;

    private String skillCode;

    private String tenantId;

    private String title;

    private String content;

    /** 扩展明细 JSON */
    private String detail;

    /** 飞书是否已推送 */
    private Integer notified;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private String ackBy;

    private LocalDateTime ackAt;

    private String ackNote;
}
