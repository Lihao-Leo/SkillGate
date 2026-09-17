package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行记录（技术方案 v6.5 §6.3）：统一异步；置终态前先落产物与结算。
 */
@Data
@TableName("execution")
public class Execution {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_CANCELLING = "CANCELLING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一任务ID */
    private String taskId;

    /** 调用方幂等键；同 AppKey 下唯一 */
    private String clientRequestId;

    private String appKeyId;

    private String tenantId;

    private String skillCode;

    /** 实际解析使用的版本号 */
    private String skillVersion;

    /** Skill 包 SHA-256，复现锚点之一 */
    private String packageSha256;

    /** 沙箱镜像 digest，复现锚点之二（worker 写入） */
    private String sandboxDigest;

    /** 完整入参 JSON 的 OSS key */
    private String inputRef;

    /** 调用方透传上下文（≤4KB），回调与终态轮询响应原样带回 */
    private String context;

    private Integer expectedCount;

    /** count 超限经 override 放行：1=是 */
    private Integer countOverridden;

    private String outputConfigSnapshot;

    private String pricingSnapshot;

    /** 计费冻结单（FREE/0 元任务为 NULL） */
    private String holdId;

    /** PENDING/RUNNING/CANCELLING/SUCCEEDED/FAILED/CANCELLED */
    private String status;

    /** 进度 0-100（report_progress 上报；NULL=不支持） */
    private Integer progress;

    /** TIMEOUT/BUDGET_EXCEEDED/EMPTY_OUTPUT/OUTPUT_LIMIT/NEED_MEDIA/INTERNAL/CANCELLED */
    private String errorCode;

    private String errorMessage;

    private String callbackUrl;

    /** PENDING/SENT/RETRYING/DEAD_LETTER/NO_CALLBACK（worker 维护） */
    private String callbackStatus;

    private Integer modelCalls;

    private Integer tokensUsed;

    private Long durationMs;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public static boolean isTerminal(String status) {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }
}
