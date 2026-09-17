package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.Execution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Execution Mapper：条件更新承载状态机不变量（防双消费 / 取消竞态）。
 */
@Mapper
public interface ExecutionMapper extends BaseMapper<Execution> {

    /**
     * PENDING 取消：条件更新，仅当仍处 PENDING 时生效；后续 MQ 消息被
     * 「status != PENDING 跳过」不变量挡掉（TC-SCH-006）。
     */
    @Update("UPDATE execution SET status = 'CANCELLED', error_code = 'CANCELLED', "
            + "error_message = 'cancelled by caller before execution', finished_at = NOW() "
            + "WHERE task_id = #{taskId} AND status = 'PENDING'")
    int cancelPending(@Param("taskId") String taskId);

    /** RUNNING → CANCELLING：worker reconciler 发现标记后杀 Job 走失败路径结算 */
    @Update("UPDATE execution SET status = 'CANCELLING' "
            + "WHERE task_id = #{taskId} AND status = 'RUNNING'")
    int markCancelling(@Param("taskId") String taskId);

    /** 租户在途任务统计（并发配额漂移修复，按 DB 真值重置 Redis 计数） */
    @Select("SELECT tenant_id AS tenantId, COUNT(*) AS running FROM execution "
            + "WHERE status IN ('PENDING', 'RUNNING', 'CANCELLING') GROUP BY tenant_id")
    List<Map<String, Object>> countRunningByTenant();
}
