package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录验证码（发送限流：同 identifier 60s 一条、当日 5 条，见 UserAuthService）。
 */
@Data
@TableName("verification_code")
public class VerificationCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String identifier;

    /** LOGIN */
    private String scene;

    private String code;

    private LocalDateTime expiresAt;

    /** 使用时间（NULL=未使用） */
    private LocalDateTime usedAt;

    private LocalDateTime createdAt;
}
