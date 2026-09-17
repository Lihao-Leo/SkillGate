package com.skill.platform.billing.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点数账户（技术方案 v6.5 §6.8）。V1：key 即账户，一 key 一账户。
 */
@Data
@TableName("credit_account")
public class CreditAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 AppKey（sk- 前缀对外标识） */
    private String appKeyId;

    /** 租户标识（冗余，报表用） */
    private String tenantId;

    /** 可用点数 */
    private Long balance;

    /** 冻结中点数（在途任务 hold 合计） */
    private Long frozen;

    /** 乐观锁版本号（冻结/结算单行原子更新时递增） */
    private Long version;

    private LocalDateTime updatedAt;
}
