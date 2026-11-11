package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * AdminUser Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
