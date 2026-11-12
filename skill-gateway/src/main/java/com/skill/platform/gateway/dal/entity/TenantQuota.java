package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户汇总限流配额（双层限流第 2 层）：行存在才启用租户级检查；字段 NULL=该维度不限。
 */
@Data
@TableName("tenant_quota")
public class TenantQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 租户聚合 QPS（NULL=不限） */
    private Integer qps;

    /** 租户并发上限（NULL=回落 key 配额/全局默认） */
    private Integer maxRunning;

    /** 租户日调用量（NULL=不限） */
    private Integer dailyLimit;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
