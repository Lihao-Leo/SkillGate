package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FREE 公开分发 Skill 埋点（技术方案 v6.5 §6.12）：采集 IO 供价值分析。
 */
@Data
@TableName("skill_io_log")
public class SkillIoLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillCode;

    /** 上报方声明版本 */
    private String version;

    /** 输入摘要 JSON（materials 元信息 + instructions） */
    private String input;

    /** 输出产物元数据 JSON（类型/数量/大小，不含文件本体） */
    private String output;

    /** SUCCEEDED / FAILED（调用方环境执行结果） */
    private String status;

    private Long durationMs;

    /** 调用方环境标识（agent 平台/来源，尽力而为） */
    private String callerHint;

    private LocalDateTime createdAt;
}
