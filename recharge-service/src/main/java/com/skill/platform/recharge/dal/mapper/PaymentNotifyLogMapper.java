package com.skill.platform.recharge.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.recharge.dal.entity.PaymentNotifyLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * PaymentNotifyLog Mapper：通用 CRUD 经 BaseMapper。
 */
@Mapper
public interface PaymentNotifyLogMapper extends BaseMapper<PaymentNotifyLog> {
}
