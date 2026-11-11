package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.AdminRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * AdminRole Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface AdminRoleMapper extends BaseMapper<AdminRole> {
}
