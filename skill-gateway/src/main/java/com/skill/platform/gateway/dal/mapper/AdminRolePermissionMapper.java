package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.AdminRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * AdminRolePermission Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface AdminRolePermissionMapper extends BaseMapper<AdminRolePermission> {
}
