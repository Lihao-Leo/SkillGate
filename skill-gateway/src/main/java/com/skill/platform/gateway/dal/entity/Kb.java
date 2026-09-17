package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识库（§6.11）：租户隔离，向量在 Milvus（collection 含租户前缀）。 */
@Data
@TableName("kb")
public class Kb {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String kbId;

    private String tenantId;

    private String name;

    /** 向量化模型 alias（经 LiteLLM） */
    private String embeddingAlias;

    private String milvusCollection;

    private Integer status;

    private LocalDateTime createdAt;
}
