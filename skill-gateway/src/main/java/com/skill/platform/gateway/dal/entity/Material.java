package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材（技术方案 v6.5 §6.5）：双通道上传的输入文件；text/link 语义类型不落库。
 */
@Data
@TableName("material")
public class Material {

    /** 0=待上传（presign 已签发未确认）1=可用 2=确认失败 */
    public static final int STATUS_PENDING_UPLOAD = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_CONFIRM_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一素材ID */
    private String materialId;

    private String tenantId;

    /** video/image/audio/document/data/other */
    private String materialType;

    /** {tenantId}/materials/{materialId}.{ext} */
    private String ossKey;

    /** multipart / presign */
    private String uploadChannel;

    private Integer status;

    private String filename;

    private String contentType;

    private Long fileSize;

    /** 内容 SHA-256（confirm 时回写校验；直传通道不可得则为 NULL） */
    private String sha256;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime lastReferencedAt;

    private LocalDateTime expiresAt;
}
