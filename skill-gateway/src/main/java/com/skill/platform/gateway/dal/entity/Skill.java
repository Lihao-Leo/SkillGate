package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 元数据（技术方案 v6.5 §6.1）。
 */
@Data
@TableName("skill")
public class Skill {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    /** 包类型：CODE=固定入口 main.py；AGENT=SKILL.md 指令型（沙箱经平台 agent runner 执行） */
    public static final String KIND_CODE = "CODE";
    public static final String KIND_AGENT = "AGENT";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public static final String PRICING_FREE = "FREE";
    public static final String PRICING_PER_EXECUTION = "PER_EXECUTION";
    public static final String PRICING_METERED = "METERED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** Skill 唯一编码，同租户下唯一 */
    private String skillCode;

    private String name;

    private String description;

    /** PRIVATE=仅本租户 PUBLIC=市场公开 */
    private String visibility;

    /** 所需平台能力 JSON 数组，如 ["llm-text","video-gen","kb"] */
    private String requiredAbilities;

    /** {"countable":true,"defaultCount":1,"maxCount":20,"timeoutSeconds":600} */
    private String outputConfig;

    /** {"mode":"PER_EXECUTION","points":10} / {"mode":"METERED","capPoints":500} / {"mode":"FREE"} */
    private String pricingConfig;

    /** 调用说明元数据 JSON（导出 markdown/openapi/tool-schema 的数据源） */
    private String invocationSpec;

    /** 埋点令牌（skt- 前缀）；NULL=非公开分发 */
    private String telemetryToken;

    /** 当前默认执行版本号 */
    /** 包类型（上传时按包内入口自动识别） */
    private String kind;

    private String defaultVersion;

    /** 1=启用 0=禁用 */
    private Integer status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
