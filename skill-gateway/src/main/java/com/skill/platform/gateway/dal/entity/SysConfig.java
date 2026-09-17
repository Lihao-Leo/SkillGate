package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台系统配置（§10.1 系统配置）：key-value 覆盖 yml 默认值（如全局配额）。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，如 quota.qps / quota.max-running / quota.daily-limit */
    private String configKey;

    private String configValue;

    private String remark;

    private LocalDateTime updatedAt;
}
