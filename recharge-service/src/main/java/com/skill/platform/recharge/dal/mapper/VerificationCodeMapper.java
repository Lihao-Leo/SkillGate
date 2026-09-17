package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.VerificationCode;
import org.apache.ibatis.annotations.Mapper;

/**
 * VerificationCode Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface VerificationCodeMapper extends BaseMapper<VerificationCode> {
}
