package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.AlertRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * AlertRule Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}
