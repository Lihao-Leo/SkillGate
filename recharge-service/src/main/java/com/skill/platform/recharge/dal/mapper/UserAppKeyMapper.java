package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.UserAppKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserAppKey Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface UserAppKeyMapper extends BaseMapper<UserAppKey> {
}
