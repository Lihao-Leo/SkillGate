package com.skill.platform.recharge.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝当面付（precreate）适配器：RSA2 签名 / 验签（V1 预留，默认关闭）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "recharge.channels.alipay", name = "enabled", havingValue = "true")
public class AlipayPrecreateChannel implements PaymentChannel {

    private final ObjectMapper objectMapper;

    @Value("${recharge.channels.alipay.app-id}")
    private String appId;
    @Value("${recharge.channels.alipay.gateway:https://openapi.alipay.com/gateway.do")
    private String gateway;
    @Value("${recharge.channels.alipay.merchant-private-key}")
    private String merchantPrivateKey;
    @Value("${recharge.channels.alipay.alipay-public-key}")
    private String alipayPublicKey;
    @Value("${recharge.channels.alipay.notify-url}")
    private String notifyUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String channel() {
        return "ALIPAY";
    }

    @Override
    public ChannelCreateResult create(RechargeOrder order) throws Exception {
        Map<String, String> biz = new TreeMap<>();
        biz.put("out_trade_no", order.getOrderNo());
        biz.put("total_amount", formatYuan(order.getAmountFen()));
        biz.put("subject", "skill-platform points");
        Map<String, String> params = baseParams("alipay.trade.precreate");
        params.put("biz_content", objectMapper.writeValueAsString(biz));
        params.put("notify_url", notifyUrl);
        String response = post(signedForm(params));
        JsonNode qr = objectMapper.readTree(response).path("alipay_trade_precreate_response");
        String codeUrl = qr.path("qr_code").asText(null);
        if (codeUrl == null || codeUrl.isBlank()) {
            throw new IllegalStateException("alipay precreate failed: code=" + qr.path("code").asText());
        }
        return new ChannelCreateResult(codeUrl, null);
    }

    @Override
    public NotifyVerification verifyNotify(String rawBody, Map<String, String> headers) {
        try {
            // 支付宝异步通知为 application/x-www-form-urlencoded：key=value&...，含 sign 与 sign_type
            Map<String, String> form = parseForm(rawBody);
            String sign = form.remove("sign");
            form.remove("sign_type");
            StringBuilder message = new StringBuilder();
            new TreeMap<>(form).forEach((k, v) -> message.append(k).append('=').append(v).append('&'));
            String signingString = message.substring(0, message.length() - 1);
            if (sign == null || !ChannelCrypto.rsaVerify(alipayPublicKey, signingString, sign)) {
                return NotifyVerification.invalid("RSA2 验签失败");
            }
            if (!"TRADE_SUCCESS".equals(form.get("trade_status"))
                    && !"TRADE_FINISHED".equals(form.get("trade_status"))) {
                return NotifyVerification.invalid("非支付成功状态: " + form.get("trade_status"));
            }
            long paidFen = Long.parseLong(form.getOrDefault("total_amount", "0").replace(".", ""));
            return new NotifyVerification(false, "ok",
                    form.get("out_trade_no"), form.get("trade_no"), paidFen);
        } catch (Exception e) {
            log.error("alipay notify parse failed", e);
            return NotifyVerification.invalid("回调报文解析失败");
        }
    }

    @Override
    public TradeStatus query(RechargeOrder order) throws Exception {
        Map<String, String> biz = Map.of("out_trade_no", order.getOrderNo());
        Map<String, String> params = baseParams("alipay.trade.query");
        params.put("biz_content", objectMapper.writeValueAsString(biz));
        JsonNode response = objectMapper.readTree(post(signedForm(params)))
                .path("alipay_trade_query_response");
        String status = response.path("trade_status").asText("");
        return switch (status) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> TradeStatus.PAID;
            case "WAIT_BUYER_PAY" -> TradeStatus.UNPAID;
            default -> TradeStatus.UNKNOWN;
        };
    }

    @Override
    public boolean closeOrder(RechargeOrder order) throws Exception {
        Map<String, String> biz = Map.of("out_trade_no", order.getOrderNo());
        Map<String, String> params = baseParams("alipay.trade.close");
        params.put("biz_content", objectMapper.writeValueAsString(biz));
        post(signedForm(params));
        return true;
    }

    @Override
    public String ackResponse(boolean success, String message) {
        return success ? "success" : "fail";
    }

    private Map<String, String> baseParams(String method) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("version", "1.0");
        return params;
    }

    private String signedForm(Map<String, String> params) {
        StringBuilder message = new StringBuilder();
        new TreeMap<>(params).forEach((k, v) -> message.append(k).append('=').append(v).append('&'));
        String signingString = message.substring(0, message.length() - 1);
        params.put("sign", ChannelCrypto.rsaSign(merchantPrivateKey, signingString));
        StringBuilder form = new StringBuilder();
        params.forEach((k, v) -> form.append(urlEncode(k)).append('=')
                .append(urlEncode(v)).append('&'));
        return form.substring(0, form.length() - 1);
    }

    private String post(String form) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(gateway))
                        .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("alipay api failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static Map<String, String> parseForm(String raw) throws Exception {
        Map<String, String> form = new java.util.HashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 分 → 元字符串（两位小数，渠道协议要求） */
    private static String formatYuan(long fen) {
        return String.format("%d.%02d", fen / 100, fen % 100);
    }
}
