package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * KB 分块（技术方案 v2.0 §2.2）：入库幂等（uk_chunk）与引用溯源的持久化锚点。
 * Milvus 侧 pk=chunkId，本表持有原文用于预览与检索结果回填。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {docId}::{idx} */
    private String chunkId;

    private String docId;

    private String kbId;

    private String tenantId;

    /** 文档内序号 */
    private Integer chunkIdx;

    /** 分块原文（预览/检索回填） */
    private String text;

    /** 内容 SHA-256（变更检测） */
    private String textHash;

    /** 实际灌入的 embedding 模型（重灌判断依据） */
    private String embedModel;

    private LocalDateTime createdAt;
}
