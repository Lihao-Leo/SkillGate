package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行事件链（技术方案 v2.0 §1.2）：观测数据，写入失败仅打日志不阻塞主链路。
 * 展示顺序按 (taskId, seq) 升序；detail 为 JSON 字符串（与既有实体 JSON→String 映射约定一致）。
 */
@Data
@TableName("execution_event")
public class ExecutionEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String tenantId;

    /** 同任务内递增序号 */
    private Integer seq;

    /** 阶段，见技术方案 §1.3 词表（ACCEPT/AUTH/.../KB_RETRIEVE） */
    private String stage;

    /** OK / FAILED / SKIPPED */
    private String status;

    /** 产生方：gateway / worker / billing */
    private String service;

    /** 该阶段耗时（毫秒，可空） */
    private Long durationMs;

    /** 扩展信息 JSON 字符串 */
    private String detail;

    /** OTel traceId（预留关联 Jaeger，可空） */
    private String traceId;

    /** 事件时间（毫秒精度） */
    private LocalDateTime occurredAt;
}
