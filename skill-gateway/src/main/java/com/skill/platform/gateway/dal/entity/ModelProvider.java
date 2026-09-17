package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型供应商配置（§6.6）：Skill 只认 alias；api_key AES 加密存储、永不出库。
 */
@Data
@TableName("model_provider")
public class ModelProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型能力别名（唯一） */
    private String alias;

    private String provider;

    /** 供应商侧模型名称 */
    private String modelName;

    private String endpoint;

    /** AES 加密后的 API Key */
    private String apiKeyCipher;

    private String fallbackAlias;

    private Integer maxQps;

    @TableField("cost_per_1k_input_tokens")
    private BigDecimal costPer1kInputTokens;

    @TableField("cost_per_1k_output_tokens")
    private BigDecimal costPer1kOutputTokens;

    private BigDecimal costPerCall;

    /** 1=启用 0=禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
