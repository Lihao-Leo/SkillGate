package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-权限点映射。
 */
@Data
@TableName("admin_role_permission")
public class AdminRolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleCode;

    private String permCode;
}
