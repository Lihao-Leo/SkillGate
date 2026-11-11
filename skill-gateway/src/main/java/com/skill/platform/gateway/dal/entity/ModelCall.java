package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 逐次模型调用明细（V2.1）：worker 沙箱每次 LLM 调用一行；
 * cost_points 由 gateway 落库时按 model_provider 费率折算（费率字段首个消费方）。
 */
@Data
@TableName("model_call")
public class ModelCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String tenantId;

    private String skillCode;

    /** 任务内序号（与 taskId 联合防重） */
    private Integer seq;

    /** chat / embeddings / video */
    private String kind;

    /** 实际命中 alias（含 fallback 后） */
    private String alias;

    private String modelName;

    private String provider;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private BigDecimal costPoints;

    private Long durationMs;

    /** OK / FAILED */
    private String status;

    private String errorCode;

    private LocalDateTime createdAt;
}
