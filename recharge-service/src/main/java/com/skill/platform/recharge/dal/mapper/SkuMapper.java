package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.Sku;
import org.apache.ibatis.annotations.Mapper;

/**
 * Sku Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface SkuMapper extends BaseMapper<Sku> {
}
