package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.SkillIoLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * SkillIoLog Mapper：通用 CRUD 经 BaseMapper，复杂查询按需补充。
 */
@Mapper
public interface SkillIoLogMapper extends BaseMapper<SkillIoLog> {
}
