package com.skill.platform.billing.dal.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 跨库访问 skill_platform（技术方案 v6.5 §4.8 前提：skill_platform 与 skill_billing
 * 为同 MySQL 实例分库；dev 单库联调时两库合一，platformSchema 由配置注入）。
 *
 * <p>仅对账任务使用，且只做只读统计与止损性取消（先查任务状态再动钱）。
 * platformSchema 为配置值（非用户输入），${} 拼接无注入面。
 */
@Mapper
public interface PlatformExecutionMapper {

    /** 产物数（补结算时 PER_EXECUTION 按 min(实际产物数, expectedCount) 计费） */
    @Select("SELECT COUNT(*) FROM `${platformSchema}`.artifact WHERE task_id = #{taskId}")
    int countArtifacts(@Param("platformSchema") String platformSchema,
                       @Param("taskId") String taskId);

    /**
     * 对账止损：任务从未执行（非终态）时先置 CANCELLED——后续 MQ 消息会被
     * 「status != PENDING 跳过」不变量挡掉，再释放冻结。
     *
     * @return 影响行数；0 = 已在扫描后竞态转终态，本轮跳过
     */
    @Update("UPDATE `${platformSchema}`.execution SET status = 'CANCELLED', "
            + "error_code = 'INTERNAL', "
            + "error_message = 'billing reconcile: FROZEN over 24h and execution not terminal', "
            + "finished_at = NOW() "
            + "WHERE task_id = #{taskId} AND status IN ('PENDING', 'RUNNING', 'CANCELLING')")
    int cancelIfNotTerminal(@Param("platformSchema") String platformSchema,
                            @Param("taskId") String taskId);
}
