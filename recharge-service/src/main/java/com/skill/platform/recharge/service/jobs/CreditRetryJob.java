package com.skill.platform.recharge.service.jobs;

import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入账重试任务（每 1min）：扫 PAID 未入账且到达重试时刻的订单重推（orderNo 幂等消化重放）；
 * 超 1h 未入账高级告警人工（§10.4）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "recharge.jobs.credit-retry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CreditRetryJob {

    private final RechargeOrderMapper orderMapper;
    private final CreditService creditService;

    @Scheduled(cron = "${recharge.jobs.credit-retry.cron:20 * * * * ?}")
    public void retryStuckCredits() {
        List<RechargeOrder> pending = orderMapper.selectCreditPending();
        for (RechargeOrder order : pending) {
            try {
                creditService.credit(order.getOrderNo());
            } catch (Exception e) {
                // credit 内部已记录退避计划；此处兜底防单条异常影响整批
                log.error("credit retry failed: orderNo={}", order.getOrderNo(), e);
            }
        }
        int stuck = orderMapper.countCreditStuck(LocalDateTime.now().minusHours(1));
        if (stuck > 0) {
            log.error("[ALERT][HIGH] paid orders not credited over 1h: count={}", stuck);
        }
        if (!pending.isEmpty() || stuck > 0) {
            log.info("credit retry pass: pending={}, stuckOver1h={}", pending.size(), stuck);
        }
    }
}
