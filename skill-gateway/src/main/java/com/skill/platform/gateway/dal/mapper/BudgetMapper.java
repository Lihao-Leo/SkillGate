package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.Budget;
import org.apache.ibatis.annotations.Mapper;

/**
 * Budget Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {
}
