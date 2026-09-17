package com.skill.platform.billing.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.billing.dal.entity.BillingTransaction;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计费流水 Mapper：不可变（只插入不更新），查询经 BaseMapper 条件构造器。
 */
@Mapper
public interface BillingTransactionMapper extends BaseMapper<BillingTransaction> {
}
