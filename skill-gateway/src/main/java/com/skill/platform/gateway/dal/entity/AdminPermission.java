package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 权限点字典（与 Permissions 常量一致；含前端菜单分组信息）。
 */
@Data
@TableName("admin_permission")
public class AdminPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String permCode;

    private String permName;

    /** 前端菜单分组 */
    private String groupName;

    private Integer sortNo;
}
