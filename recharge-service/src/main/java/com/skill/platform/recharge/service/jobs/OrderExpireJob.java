package com.skill.platform.recharge.service.jobs;

import com.skill.platform.recharge.channel.PaymentChannel;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.service.CreditService;
import com.skill.platform.recharge.channel.ChannelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查单兜底任务（每 1min，§10.4「查单兜底」）：
 * 扫临期/已过期订单（expire_at < now+2min）主动查渠道——
 * 已支付补 PAID + 入账（回调丢失场景）；未支付且已过期则渠道关单 + 置 EXPIRED。
 * 与执行平台「先查成因再动钱」同理念。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "recharge.jobs.order-expire", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderExpireJob {

    private final RechargeOrderMapper orderMapper;
    private final ChannelRegistry channelRegistry;
    private final CreditService creditService;

    @Scheduled(cron = "${recharge.jobs.order-expire.cron:40 * * * * ?}")
    public void reconcileExpiring() {
        List<RechargeOrder> expiring = orderMapper.selectExpiring(LocalDateTime.now().plusMinutes(2));
        for (RechargeOrder order : expiring) {
            try {
                handle(order);
            } catch (Exception e) {
                log.error("expire reconcile failed: orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    private void handle(RechargeOrder order) throws Exception {
        if (RechargeOrder.STATUS_CREATED.equals(order.getStatus())) {
            // 渠道下单未成功（无渠道交易）：直接过期
            orderMapper.markExpired(order.getOrderNo());
            log.info("order expired (channel create never succeeded): orderNo={}", order.getOrderNo());
            return;
        }
        PaymentChannel channel = channelRegistry.channel(order.getChannel());
        boolean expired = order.getExpireAt().isBefore(LocalDateTime.now());
        PaymentChannel.TradeStatus status = channel.query(order);
        switch (status) {
            case PAID -> {
                // 回调丢失：补 PAID + 入账
                if (orderMapper.markPaidByQuery(order.getOrderNo(), order.getOrderNo()) == 1) {
                    log.info("order paid found by query, compensating: orderNo={}", order.getOrderNo());
                    creditService.credit(order.getOrderNo());
                }
            }
            case UNPAID -> {
                if (expired) {
                    try {
                        channel.closeOrder(order);
                    } catch (Exception closeFailure) {
                        log.warn("channel close failed (best-effort): orderNo={}", order.getOrderNo());
                    }
                    orderMapper.markExpired(order.getOrderNo());
                    log.info("order expired after close: orderNo={}", order.getOrderNo());
                }
                // 临期未过期：等下一轮
            }
            default -> log.warn("channel query unknown, skip this round: orderNo={}, state={}",
                    order.getOrderNo(), status);
        }
    }
}
