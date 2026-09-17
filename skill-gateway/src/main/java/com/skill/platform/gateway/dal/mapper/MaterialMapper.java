package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.Material;
import org.apache.ibatis.annotations.Mapper;

/**
 * Material Mapper：通用 CRUD 经 BaseMapper，复杂查询按需补充。
 */
@Mapper
public interface MaterialMapper extends BaseMapper<Material> {
}
