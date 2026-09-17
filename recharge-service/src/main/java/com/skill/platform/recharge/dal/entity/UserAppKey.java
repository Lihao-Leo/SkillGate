package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 ↔ AppKey 映射（user 与 AppKey 一对多；首次购买自动签发并置 primary，
 * 后续充值默认入 primary key 的账户）。
 *
 * <p>secret_pending：签发/重置时的一次性展示密文（AES 加密），首次展示后清空——
 * 完整值永不以明文落库超过展示窗口。
 */
@Data
@TableName("user_app_key")
public class UserAppKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String appKeyId;

    /** 1=主 key（入账默认账户） */
    private Integer isPrimary;

    /** 待展示的 AppSecret 密文（AES，一次性；NULL=无待展示） */
    private String secretPending;

    private LocalDateTime createdAt;
}
