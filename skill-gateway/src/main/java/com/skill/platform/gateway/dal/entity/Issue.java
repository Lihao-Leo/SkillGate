package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题工单（V2.3）：告警 / 失败任务 / 手工三类来源；uk(type, source_id) 幂等转单。
 * 状态机 OPEN→FIXING→RESOLVED→CLOSED（+ RESOLVED→FIXING 回退；CLOSED 终态不可逆）。
 */
@Data
@TableName("issue")
public class Issue {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_FIXING = "FIXING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String TYPE_ALERT = "ALERT";
    public static final String TYPE_EXECUTION_FAILED = "EXECUTION_FAILED";
    public static final String TYPE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketNo;

    private String title;

    /** 来源类型：ALERT / EXECUTION_FAILED / MANUAL */
    private String type;

    /** INFO / WARN / CRITICAL */
    private String severity;

    private String status;

    /** 来源标识：alert_record.id 或 execution.task_id（MANUAL 可空） */
    private String sourceId;

    private String relatedTaskId;

    private String tenantId;

    private String skillCode;

    private String description;

    private String rootCause;

    private String resolution;

    private String assignee;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime closedAt;
}
