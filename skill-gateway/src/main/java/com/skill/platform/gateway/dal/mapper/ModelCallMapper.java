package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.ModelCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * ModelCall Mapper：通用 CRUD 经 BaseMapper，聚合查询用于监控指标。
 */
@Mapper
public interface ModelCallMapper extends BaseMapper<ModelCall> {

    /** 时间窗内 token 总量（监控：tokensWindow） */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM model_call WHERE created_at >= #{since}")
    long sumTokensSince(String since);

    /** 时间窗内折算费用合计（监控：costPointsWindow） */
    @Select("SELECT COALESCE(SUM(cost_points), 0) FROM model_call WHERE created_at >= #{since}")
    java.math.BigDecimal sumCostSince(String since);

    /** 单 Skill 时间窗内折算费用合计（单 Skill 监控 / 首页） */
    @Select("SELECT COALESCE(SUM(cost_points), 0) FROM model_call "
            + "WHERE skill_code = #{skillCode} AND created_at >= #{since}")
    java.math.BigDecimal sumCostBySkillSince(String skillCode, String since);
}
