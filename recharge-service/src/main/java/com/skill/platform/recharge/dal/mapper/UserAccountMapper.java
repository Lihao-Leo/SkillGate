package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserAccount Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
