package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行产物（技术方案 v6.5 §6.4）：worker 采集上传 OSS 后落库。
 */
@Data
@TableName("artifact")
public class Artifact {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一产物ID，如 art_20260912_001 */
    private String artifactId;

    private String taskId;

    private String tenantId;

    /** video/image/audio/document/json/text */
    private String type;

    /** {tenantId}/artifacts/{taskId}/{filename} */
    private String ossKey;

    private Long fileSize;

    private String contentType;

    /** 扩展元数据 JSON（视频时长、分辨率等） */
    private String meta;

    private LocalDateTime createdAt;

    /** 过期清理时间（默认90天）；NULL=永久保留 */
    private LocalDateTime expiresAt;
}
