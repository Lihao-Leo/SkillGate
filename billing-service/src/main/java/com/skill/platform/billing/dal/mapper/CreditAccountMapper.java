package com.skill.platform.billing.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.billing.dal.entity.CreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 点数账户 Mapper。核心操作为单行原子条件更新（技术方案 v6.5 §4.8 真实 SQL），
 * 天然防并发花超，无需分布式锁。
 */
@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    /**
     * 冻结：balance → frozen 单行原子迁移。
     *
     * @return 影响行数；0 = 账户不存在或余额不足
     */
    @Update("UPDATE credit_account SET balance = balance - #{amount}, frozen = frozen + #{amount}, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE app_key_id = #{appKeyId} AND balance >= #{amount}")
    int freeze(@Param("appKeyId") String appKeyId, @Param("amount") long amount);

    /**
     * 结算：frozen 减去冻结额，未消费差额（holdAmount - charged）回流 balance。
     */
    @Update("UPDATE credit_account SET frozen = frozen - #{holdAmount}, balance = balance + #{refund}, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE app_key_id = #{appKeyId}")
    int settle(@Param("appKeyId") String appKeyId,
               @Param("holdAmount") long holdAmount,
               @Param("refund") long refund);

    /**
     * 释放（全额退款）：frozen 全额回流 balance。
     */
    @Update("UPDATE credit_account SET frozen = frozen - #{amount}, balance = balance + #{amount}, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE app_key_id = #{appKeyId}")
    int release(@Param("appKeyId") String appKeyId, @Param("amount") long amount);

    /**
     * 充值/正向调整。
     */
    @Update("UPDATE credit_account SET balance = balance + #{points}, version = version + 1, "
            + "updated_at = NOW() WHERE app_key_id = #{appKeyId}")
    int addBalance(@Param("appKeyId") String appKeyId, @Param("points") long points);

    /**
     * 结算后余额快照（同事务内行锁读取，写流水 balance_after 用）。
     */
    @Select("SELECT balance FROM credit_account WHERE app_key_id = #{appKeyId} FOR UPDATE")
    Long selectBalanceForUpdate(@Param("appKeyId") String appKeyId);
}
