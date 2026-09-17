package com.skill.platform.gateway.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.billing.BillingViews.BalanceView;
import com.skill.platform.gateway.billing.BillingViews.FreezeResult;
import com.skill.platform.gateway.billing.BillingViews.SettlementView;
import com.skill.platform.gateway.billing.BillingViews.TransactionPage;
import com.skill.platform.gateway.billing.BillingViews.TransactionView;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * billing-service HTTP 实现：X-Internal-Token 认证；连接 2s / 读 5s 超时；
 * 错误码原样映射（40201 余额不足等语义跨服务透传给调用方）。
 */
@Slf4j
@Component
public class HttpBillingClient implements BillingClient {

    private static final ParameterizedTypeReference<Envelope<JsonNode>> ENVELOPE =
            new ParameterizedTypeReference<>() {
            };
    private static final com.fasterxml.jackson.core.type.TypeReference<Envelope<JsonNode>> ERROR_ENVELOPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpBillingClient(@Value("${skill-platform.billing.base-url}") String baseUrl,
                             @Value("${skill-platform.billing.internal-token}") String internalToken,
                             ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
                .build();
    }

    @Override
    public void ensureAccount(String appKeyId, String tenantId) {
        post("/internal/billing/accounts", Map.of("appKeyId", appKeyId, "tenantId", tenantId));
    }

    @Override
    public FreezeResult freeze(String taskId, String appKeyId, long amount) {
        JsonNode data = post("/internal/billing/freeze",
                Map.of("taskId", taskId, "appKeyId", appKeyId, "amount", amount));
        return parse(data, FreezeResult.class);
    }

    @Override
    public void release(String holdId) {
        post("/internal/billing/release", Map.of("holdId", holdId));
    }

    @Override
    public void releaseByTask(String taskId) {
        post("/internal/billing/release", Map.of("taskId", taskId));
    }

    @Override
    public SettlementView settlementOf(String taskId) {
        JsonNode data = get("/internal/billing/holds", Map.of("taskId", taskId));
        return parse(data, SettlementView.class);
    }

    @Override
    public BalanceView balance(String appKeyId) {
        JsonNode data = get("/internal/billing/balance", Map.of("appKeyId", appKeyId));
        return parse(data, BalanceView.class);
    }

    @Override
    public TransactionPage transactions(String appKeyId, String taskId, int pageNo, int pageSize) {
        Map<String, String> query = new java.util.HashMap<>(Map.of(
                "appKeyId", appKeyId, "pageNo", String.valueOf(pageNo),
                "pageSize", String.valueOf(pageSize)));
        if (taskId != null && !taskId.isBlank()) {
            query.put("taskId", taskId);
        }
        JsonNode data = get("/internal/billing/transactions", query);
        if (data == null || data.isNull()) {
            return new TransactionPage(0, pageNo, pageSize, List.of());
        }
        List<TransactionView> items = new java.util.ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        for (JsonNode item : data.path("items")) {
            items.add(new TransactionView(
                    item.path("txId").asText(null),
                    item.path("type").asText(null),
                    item.path("taskId").asText(null),
                    item.path("amount").asLong(),
                    item.path("balanceAfter").asLong(),
                    item.path("remark").asText(null),
                    item.hasNonNull("createdAt") ? LocalDateTime.parse(item.get("createdAt").asText(), formatter) : null));
        }
        return new TransactionPage(data.path("total").asLong(), data.path("pageNo").asLong(),
                data.path("pageSize").asLong(), items);
    }

    @Override
    public BillingViews.RechargeResult recharge(String appKeyId, String tenantId, long points,
                                                String orderNo) {
        JsonNode data = post("/internal/billing/recharge", Map.of(
                "appKeyId", appKeyId,
                "tenantId", tenantId == null ? "" : tenantId,
                "points", points,
                "orderNo", orderNo));
        return parse(data, BillingViews.RechargeResult.class);
    }

    // ------------------------------------------------------------------
    // 通用收发：信封解析 + 错误码映射
    // ------------------------------------------------------------------

    private JsonNode post(String path, Object body) {
        try {
            Envelope<JsonNode> envelope = client.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(ENVELOPE);
            return unwrap(envelope);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    private JsonNode get(String path, Map<String, String> query) {
        try {
            Envelope<JsonNode> envelope = client.get()
                    .uri(uri -> {
                        uri.path(path);
                        query.forEach(uri::queryParam);
                        return uri.build();
                    })
                    .retrieve()
                    .body(ENVELOPE);
            return unwrap(envelope);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    private JsonNode unwrap(Envelope<JsonNode> envelope) {
        if (envelope == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "billing 无响应");
        }
        if (envelope.code() != 0) {
            throw new BizException(mapCode(envelope.code()),
                    envelope.message() == null ? "billing 错误" : envelope.message());
        }
        return envelope.data();
    }

    /** billing 的 4xx/5xx 响应体同为 ApiResponse 信封，透传业务码 */
    private BizException translate(RestClientResponseException e) {
        try {
            Envelope<JsonNode> envelope = objectMapper.readValue(
                    e.getResponseBodyAsString(), ERROR_ENVELOPE);
            if (envelope != null && envelope.code() != 0) {
                return new BizException(mapCode(envelope.code()),
                        envelope.message() == null ? "billing 错误" : envelope.message());
            }
        } catch (Exception ignore) {
            // 响应体非信封，走通用错误
        }
        return new BizException(ErrorCode.INTERNAL_ERROR, "billing 调用失败: HTTP " + e.getStatusCode().value());
    }

    private ErrorCode mapCode(int code) {
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.code() == code) {
                return errorCode;
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private <T> T parse(JsonNode data, Class<T> type) {
        if (data == null || data.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(data, type);
        } catch (Exception e) {
            log.error("parse billing response failed", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "billing 响应解析失败");
        }
    }

    private record Envelope<T>(int code, String message, T data) {
    }
}
