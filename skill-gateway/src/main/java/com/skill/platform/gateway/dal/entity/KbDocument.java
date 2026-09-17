package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识库文档（§6.11）：原文在 OSS，向量入库状态在此跟踪。 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;

    private String kbId;

    private String tenantId;

    private String ossKey;

    /** 已入库分块数 */
    private Integer chunkCount;

    /** 0=待入库 1=已入库 2=失败 */
    private Integer status;

    private LocalDateTime createdAt;
}
