package com.skill.platform.recharge.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock 渠道（local/联调，生产禁用）：
 * <ul>
 *   <li>create → 确定性伪二维码 mockpay://qr/{orderNo}</li>
 *   <li>verifyNotify → Header X-Mock-Signature = HMAC-SHA256(secret, body)；
 *       body JSON {outTradeNo, tradeNo, paidAmountFen}</li>
 *   <li>query → 由测试/联调用 {@link #markPaid} 控制</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "recharge.channels.mock", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MockChannel implements PaymentChannel {

    private final ObjectMapper objectMapper;

    @Value("${recharge.channels.mock.secret:dev-mock-channel-secret}")
    private String mockSecret;

    /** 联调/测试可控的渠道侧支付状态 */
    private static final ConcurrentMap<String, Boolean> PAID = new ConcurrentHashMap<>();

    public static void markPaid(String orderNo, boolean paid) {
        PAID.put(orderNo, paid);
    }

    public static void reset() {
        PAID.clear();
    }

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public ChannelCreateResult create(RechargeOrder order) {
        return new ChannelCreateResult("mockpay://qr/" + order.getOrderNo(), null);
    }

    @Override
    public NotifyVerification verifyNotify(String rawBody, Map<String, String> headers) {
        String signature = headers.get("X-Mock-Signature");
        if (signature == null || !signature.equalsIgnoreCase(
                ChannelCrypto.hmacSha256(mockSecret, rawBody))) {
            return NotifyVerification.invalid("Mock 签名校验失败");
        }
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            return new NotifyVerification(false, "ok",
                    body.path("outTradeNo").asText(null),
                    body.path("tradeNo").asText(null),
                    body.path("paidAmountFen").asLong());
        } catch (Exception e) {
            return NotifyVerification.invalid("Mock 报文解析失败");
        }
    }

    @Override
    public TradeStatus query(RechargeOrder order) {
        return Boolean.TRUE.equals(PAID.get(order.getOrderNo())) ? TradeStatus.PAID : TradeStatus.UNPAID;
    }

    @Override
    public boolean closeOrder(RechargeOrder order) {
        PAID.remove(order.getOrderNo());
        return true;
    }

    @Override
    public String ackResponse(boolean success, String message) {
        return success ? "{\"code\":\"SUCCESS\"}" : "{\"code\":\"FAIL\",\"message\":\"" + message + "\"}";
    }
}
