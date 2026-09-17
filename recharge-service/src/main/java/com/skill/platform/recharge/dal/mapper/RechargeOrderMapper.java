package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值订单 Mapper：状态机条件更新做幂等（方案 §10.4）。
 */
@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /** CREATED → PAYING：渠道统一下单成功（带 code_url 与有效期） */
    @Update("UPDATE recharge_order SET status = 'PAYING', code_url = #{codeUrl}, "
            + "expire_at = #{expireAt} WHERE order_no = #{orderNo} AND status = 'CREATED'")
    int markPaying(@Param("orderNo") String orderNo, @Param("codeUrl") String codeUrl,
                   @Param("expireAt") LocalDateTime expireAt);

    /** PAYING → PAID：回调验签通过且金额一致；影响行数=0 即重复回调（直接应答成功） */
    @Update("UPDATE recharge_order SET status = 'PAID', trade_no = #{tradeNo}, "
            + "paid_amount_fen = #{paidAmountFen}, paid_at = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 'PAYING'")
    int markPaid(@Param("orderNo") String orderNo, @Param("tradeNo") String tradeNo,
                 @Param("paidAmountFen") long paidAmountFen);

    /** 查单兜底补 PAID：同幂等语义（渠道已支付但回调丢失场景） */
    @Update("UPDATE recharge_order SET status = 'PAID', trade_no = #{tradeNo}, "
            + "paid_amount_fen = amount_fen, paid_at = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 'PAYING'")
    int markPaidByQuery(@Param("orderNo") String orderNo, @Param("tradeNo") String tradeNo);

    /** PAID → CREDITED：入账（+ 首次购买签发）成功 */
    @Update("UPDATE recharge_order SET status = 'CREDITED', credited_at = NOW(), "
            + "retry_count = 0, next_retry_at = NULL, last_error = NULL "
            + "WHERE order_no = #{orderNo} AND status = 'PAID'")
    int markCredited(@Param("orderNo") String orderNo);

    /** 外调失败：留在 PAID，按退避计划下次重试 */
    @Update("UPDATE recharge_order SET retry_count = #{retryCount}, "
            + "next_retry_at = #{nextRetryAt}, last_error = #{lastError} "
            + "WHERE order_no = #{orderNo} AND status = 'PAID'")
    int recordCreditFailure(@Param("orderNo") String orderNo, @Param("retryCount") int retryCount,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt,
                            @Param("lastError") String lastError);

    /** CREATED/PAYING → EXPIRED：过期未付（渠道关单后） */
    @Update("UPDATE recharge_order SET status = 'EXPIRED' "
            + "WHERE order_no = #{orderNo} AND status IN ('CREATED', 'PAYING')")
    int markExpired(@Param("orderNo") String orderNo);

    /** 重试任务：PAID 未入账且到达重试时刻 */
    @Select("SELECT * FROM recharge_order WHERE status = 'PAID' AND credited_at IS NULL "
            + "AND (next_retry_at IS NULL OR next_retry_at <= NOW()) ORDER BY paid_at LIMIT 100")
    List<RechargeOrder> selectCreditPending();

    /** 超 1h 未入账（高级告警人工） */
    @Select("SELECT COUNT(*) FROM recharge_order WHERE status = 'PAID' AND credited_at IS NULL "
            + "AND paid_at < #{threshold}")
    int countCreditStuck(@Param("threshold") LocalDateTime threshold);

    /** 临期/已过期订单（查单兜底：临期主动查渠道，已过期关单置 EXPIRED） */
    @Select("SELECT * FROM recharge_order WHERE status IN ('CREATED', 'PAYING') "
            + "AND expire_at < #{threshold} ORDER BY expire_at LIMIT 100")
    List<RechargeOrder> selectExpiring(@Param("threshold") LocalDateTime threshold);
}
