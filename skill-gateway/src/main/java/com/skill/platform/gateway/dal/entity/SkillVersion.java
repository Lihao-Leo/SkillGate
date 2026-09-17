package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 版本（技术方案 v6.5 §6.2）：包永不覆盖，历史任务可复现。
 */
@Data
@TableName("skill_version")
public class SkillVersion {

    /** 0=已上传待审核 1=已发布可执行 2=已废弃不可执行 */
    public static final int STATUS_UPLOADED = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_DEPRECATED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skillId;

    /** 语义化版本号，同一 skill 下唯一 */
    private String version;

    /** skill/{tenantId}/{skillCode}/{version}/skill.zip（桶内相对路径） */
    private String ossKey;

    /** 对象绝对访问地址（bucket endpoint + key，稳定指针；实际读写仍走预签名） */
    private String ossUrl;

    /** 包 SHA-256：完整性校验 + 复现锚点 + 沙箱缓存 key */
    private String packageSha256;

    private Long packageSize;

    private String changelog;

    private Integer status;

    private String uploadedBy;

    private LocalDateTime createdAt;
}
