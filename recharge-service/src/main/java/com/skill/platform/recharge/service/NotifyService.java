package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.channel.PaymentChannel;
import com.skill.platform.recharge.common.Ids;
import com.skill.platform.recharge.dal.entity.PaymentNotifyLog;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.mapper.PaymentNotifyLogMapper;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 支付回调处理（§10.4 安全与幂等五条）：
 * 原始报文全量留档 → 渠道验签 → 金额校验（分）→ 条件更新 PAYING→PAID（重复回调应答成功）
 * → 触发入账（失败不阻塞应答，由重试任务推进）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final PaymentNotifyLogMapper notifyLogMapper;
    private final RechargeOrderMapper orderMapper;
    private final CreditService creditService;

    /**
     * @return 处理结论（调用方据此按渠道协议应答成功 / 失败）
     */
    public NotifyOutcome handle(String channelName, String rawBody, Map<String, String> headers) {
        // 1. 原始报文全量留档（审计 + 排障回放），后续更新校验结论
        PaymentNotifyLog logEntry = new PaymentNotifyLog();
        logEntry.setNotifyId(Ids.next("ntf_"));
        logEntry.setChannel(channelName.toUpperCase());
        logEntry.setRawBody(rawBody);
        logEntry.setSignatureValid(0);
        logEntry.setProcessed(0);
        notifyLogMapper.insert(logEntry);

        PaymentChannel channel = creditService.channelRegistry().channel(channelName);
        PaymentChannel.NotifyVerification verification = channel.verifyNotify(rawBody, headers);
        if (verification.invalid()) {
            updateLog(logEntry, 0, verification.message(), null, null);
            log.error("[ALERT][HIGH] payment notify verify failed: channel={}, notifyId={}",
                    channelName, logEntry.getNotifyId());
            return new NotifyOutcome(false, verification.message(), null);
        }
        logEntry.setOrderNo(verification.outTradeNo());
        logEntry.setAmountFen(verification.paidAmountFen());

        // 2. 金额校验：实付 == 应付才进 PAID（防篡改）
        RechargeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, verification.outTradeNo()).last("LIMIT 1"));
        if (order == null) {
            updateLog(logEntry, 1, "订单不存在: " + verification.outTradeNo(), null, null);
            return new NotifyOutcome(false, "order not found", null);
        }
        if (verification.paidAmountFen() == null
                || verification.paidAmountFen().longValue() != order.getAmountFen().longValue()) {
            updateLog(logEntry, 1, "金额不符: expect " + order.getAmountFen()
                    + " got " + verification.paidAmountFen(), order.getOrderNo(), order.getStatus());
            log.error("[ALERT][HIGH] payment notify amount mismatch: orderNo={}, expect={}, got={}",
                    order.getOrderNo(), order.getAmountFen(), verification.paidAmountFen());
            return new NotifyOutcome(false, "amount mismatch", null);
        }

        // 3. 回调幂等：条件更新 PAYING→PAID，影响行数=0 即重复回调，直接应答成功
        int updated = orderMapper.markPaid(order.getOrderNo(), verification.tradeNo(),
                verification.paidAmountFen());
        if (updated == 0) {
            updateLog(logEntry, 1, "幂等重复回调（已 " + order.getStatus() + "）",
                    order.getOrderNo(), order.getStatus());
            return new NotifyOutcome(true, "duplicate", order);
        }
        updateLog(logEntry, 1, "ok", order.getOrderNo(), RechargeOrder.STATUS_PAID);
        log.info("order paid: orderNo={}, tradeNo={}, amountFen={}",
                order.getOrderNo(), verification.tradeNo(), verification.paidAmountFen());

        // 4. 先应答后补账：入账失败由重试任务推进（PAID 未入账扫描），不向渠道报错
        try {
            creditService.credit(order.getOrderNo());
        } catch (Exception e) {
            log.warn("credit after notify failed, retry job will cover: orderNo={}",
                    order.getOrderNo(), e);
        }
        return new NotifyOutcome(true, "ok", order);
    }

    private void updateLog(PaymentNotifyLog logEntry, int signatureValid, String message,
                           String orderNo, String orderStatus) {
        logEntry.setSignatureValid(signatureValid);
        logEntry.setVerifyMessage(message + (orderStatus == null ? "" : "，状态 " + orderStatus));
        notifyLogMapper.updateById(logEntry);
    }

    /**
     * @param success 渠道应答是否为成功（重复回调 / 已处理均为成功）
     * @param order   成功推进到的订单（可为 null）
     */
    public record NotifyOutcome(boolean success, String message, RechargeOrder order) {
    }
}
