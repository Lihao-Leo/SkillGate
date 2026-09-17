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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 微信 Native 支付适配器（APIv3）。
 *
 * <p>安全要点：请求经 RSA-SHA256 商户私钥签名（Authorization: WECHATPAY2-SHA256-RSA2048）；
 * 回调验签用平台公钥校验 Wechatpay-Signature（timestamp\nnonce\nbody\n），资源经 AES-256-GCM 解密。
 * 商户号 / 证书 / APIv3 key 经配置（Secret）注入，永不落日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "recharge.channels.wechat", name = "enabled", havingValue = "true")
public class WeChatNativeChannel implements PaymentChannel {

    private final ObjectMapper objectMapper;

    @Value("${recharge.channels.wechat.appid}")
    private String appId;
    @Value("${recharge.channels.wechat.mchid}")
    private String mchId;
    @Value("${recharge.channels.wechat.merchant-serial}")
    private String merchantSerial;
    @Value("${recharge.channels.wechat.merchant-private-key}")
    private String merchantPrivateKey;
    @Value("${recharge.channels.wechat.platform-public-key}")
    private String platformPublicKey;
    @Value("${recharge.channels.wechat.apiv3-key}")
    private String apiV3Key;
    @Value("${recharge.channels.wechat.notify-url}")
    private String notifyUrl;
    @Value("${recharge.channels.wechat.base-url:https://api.mch.weixin.qq.com}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String channel() {
        return "WECHAT";
    }

    @Override
    public ChannelCreateResult create(RechargeOrder order) throws Exception {
        String path = "/v3/pay/transactions/native";
        String body = objectMapper.writeValueAsString(Map.of(
                "appid", appId,
                "mchid", mchId,
                "description", "skill-platform points",
                "out_trade_no", order.getOrderNo(),
                "time_expire", formatRfc3339(order.getExpireAt()),
                "notify_url", notifyUrl,
                "amount", Map.of("total", order.getAmountFen(), "currency", "CNY")));
        JsonNode response = exchange("POST", path, body);
        String codeUrl = response.path("code_url").asText(null);
        if (codeUrl == null || codeUrl.isBlank()) {
            throw new IllegalStateException("wechat native create failed: " + response);
        }
        return new ChannelCreateResult(codeUrl, null);
    }

    @Override
    public NotifyVerification verifyNotify(String rawBody, Map<String, String> headers) {
        String timestamp = headers.get("Wechatpay-Timestamp");
        String nonce = headers.get("Wechatpay-Nonce");
        String signature = headers.get("Wechatpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            return NotifyVerification.invalid("缺少 Wechatpay 签名头");
        }
        boolean verified = ChannelCrypto.rsaVerify(platformPublicKey,
                timestamp + "\n" + nonce + "\n" + rawBody + "\n", signature);
        if (!verified) {
            return NotifyVerification.invalid("平台公钥验签失败");
        }
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            if (!"TRANSACTION.SUCCESS".equals(body.path("event_type").asText())) {
                return NotifyVerification.invalid("非支付成功事件: " + body.path("event_type").asText());
            }
            JsonNode resource = body.path("resource");
            String decrypted = ChannelCrypto.aesGcmDecrypt(apiV3Key,
                    resource.path("ciphertext").asText(),
                    resource.path("nonce").asText(),
                    resource.path("associated_data").asText(null));
            JsonNode transaction = objectMapper.readTree(decrypted);
            long paidFen = transaction.path("amount").path("payer_total").asLong(
                    transaction.path("amount").path("total").asLong());
            return new NotifyVerification(false, "ok",
                    transaction.path("out_trade_no").asText(null),
                    transaction.path("transaction_id").asText(null),
                    paidFen);
        } catch (Exception e) {
            log.error("wechat notify decrypt failed", e);
            return NotifyVerification.invalid("回调资源解密失败");
        }
    }

    @Override
    public TradeStatus query(RechargeOrder order) throws Exception {
        JsonNode response = exchange("GET",
                "/v3/pay/transactions/out-trade-no/" + order.getOrderNo() + "?mchid=" + mchId, null);
        String state = response.path("trade_state").asText("");
        return switch (state) {
            case "SUCCESS" -> TradeStatus.PAID;
            case "NOTPAY", "USERPAYING" -> TradeStatus.UNPAID;
            default -> TradeStatus.UNKNOWN;
        };
    }

    @Override
    public boolean closeOrder(RechargeOrder order) throws Exception {
        String path = "/v3/pay/transactions/out-trade-no/" + order.getOrderNo() + "/close";
        exchange("POST", path, objectMapper.writeValueAsString(Map.of("mchid", mchId)));
        return true;
    }

    @Override
    public String ackResponse(boolean success, String message) {
        return success ? "{\"code\":\"SUCCESS\"}" : "{\"code\":\"FAIL\",\"message\":\"" + message + "\"}";
    }

    /** 带商户签名的 v3 请求-响应 */
    private JsonNode exchange(String method, String pathAndQuery, String body) throws Exception {
        String timestamp = String.valueOf(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String canonicalUrl = pathAndQuery.contains("?")
                ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) + "\n"
                + pathAndQuery.substring(pathAndQuery.indexOf('?') + 1) + "\n"
                : pathAndQuery + "\n\n";
        String message = method + "\n" + canonicalUrl + timestamp + "\n" + nonce + "\n"
                + (body == null ? "" : body) + "\n";
        String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId
                + "\",nonce_str=\"" + nonce + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + merchantSerial + "\",signature=\""
                + ChannelCrypto.rsaSign(merchantPrivateKey, message) + "\"";

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("User-Agent", "skill-platform-recharge");
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                    StandardCharsets.UTF_8));
        } else {
            request.GET();
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            // 商户凭证/网络类错误统一抛给上层重试；不做任何敏感信息回显
            throw new IllegalStateException("wechat api failed: HTTP " + response.statusCode());
        }
        return response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private static String formatRfc3339(LocalDateTime time) {
        return time.atOffset(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }
}
