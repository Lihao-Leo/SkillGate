package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.Issue;
import org.apache.ibatis.annotations.Mapper;

/**
 * Issue Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface IssueMapper extends BaseMapper<Issue> {
}
