package com.skill.platform.billing.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.billing.dal.entity.BillingHold;
import com.skill.platform.billing.dto.OverdueHold;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费冻结单 Mapper。结算/释放按 hold 状态幂等：SELECT ... FOR UPDATE 后校验 FROZEN 才动账。
 */
@Mapper
public interface BillingHoldMapper extends BaseMapper<BillingHold> {

    @Select("SELECT * FROM billing_hold WHERE task_id = #{taskId} FOR UPDATE")
    BillingHold selectByTaskIdForUpdate(@Param("taskId") String taskId);

    @Select("SELECT * FROM billing_hold WHERE hold_id = #{holdId} FOR UPDATE")
    BillingHold selectByHoldIdForUpdate(@Param("holdId") String holdId);

    @Select("SELECT * FROM billing_hold WHERE task_id = #{taskId}")
    BillingHold selectByTaskId(@Param("taskId") String taskId);

    /**
     * 对账扫描（技术方案 v6.5 §4.8）：FROZEN 超 cutoff 未结算的冻结单，
     * LEFT JOIN skill_platform.execution 判断任务状态（同 MySQL 实例分库前提）。
     *
     * <p>platformSchema 为配置注入的库名（prod=skill_platform 分库 / dev=dev-skill 单库），
     * 非用户输入，${} 拼接无注入面。
     */
    @Select("SELECT h.hold_id AS holdId, h.task_id AS taskId, h.app_key_id AS appKeyId, "
            + "h.amount AS amount, h.created_at AS createdAt, "
            + "e.status AS execStatus, e.expected_count AS expectedCount, "
            + "e.pricing_snapshot AS pricingSnapshot "
            + "FROM billing_hold h LEFT JOIN `${platformSchema}`.execution e ON e.task_id = h.task_id "
            + "WHERE h.status = 'FROZEN' AND h.created_at < #{cutoff}")
    List<OverdueHold> selectOverdue(@Param("cutoff") LocalDateTime cutoff,
                                    @Param("platformSchema") String platformSchema);
}
