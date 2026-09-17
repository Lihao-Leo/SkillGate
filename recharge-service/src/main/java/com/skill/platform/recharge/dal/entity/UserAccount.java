package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值平台用户（V1 最简：手机号 / 邮箱 + 验证码登录）。
 */
@Data
@TableName("user_account")
public class UserAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号或邮箱（唯一） */
    private String identifier;

    /** PHONE / EMAIL */
    private String identifierType;

    /** 1=正常 0=禁用 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
}
