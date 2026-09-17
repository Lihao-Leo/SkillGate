package com.skill.platform.recharge.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * gateway 管理面 HTTP 实现：X-Admin-Token 专属凭据（最小权限：签发与入账为主路径）；
 * 连接 2s / 读 5s 超时；错误统一抛 {@link BizException}（50001）由入账重试任务推进。
 */
@Slf4j
@Component
public class HttpSkillPlatformClient implements SkillPlatformClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpSkillPlatformClient(@Value("${recharge.platform.base-url}") String baseUrl,
                                   @Value("${recharge.platform.admin-token}") String adminToken,
                                   ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Admin-Token", adminToken)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
                .build();
    }

    @Override
    public IssuedKey issueKey(String tenantId) {
        JsonNode data = post("/api/v1/admin/app-keys", Map.of("tenantId", tenantId));
        return new IssuedKey(text(data, "appKeyId"), text(data, "appSecret"));
    }

    @Override
    public Recharged recharge(String appKeyId, long points, String orderNo) {
        JsonNode data = post("/api/v1/admin/credits/recharge", Map.of(
                "appKeyId", appKeyId, "points", points, "orderNo", orderNo));
        return new Recharged(text(data, "txId"), data.path("points").asLong(),
                data.path("balance").asLong(), data.path("alreadyCredited").asBoolean(false));
    }

    @Override
    public ResetSecret resetSecret(String appKeyId) {
        JsonNode data = exchange(client.post()
                .uri("/api/v1/admin/app-keys/{id}/reset-secret", appKeyId));
        return new ResetSecret(text(data, "appKeyId"), text(data, "appSecret"));
    }

    @Override
    public void updateKeyStatus(String appKeyId, int status) {
        exchange(client.put()
                .uri("/api/v1/admin/app-keys/{id}/status", appKeyId)
                .body(Map.of("status", status)));
    }

    @Override
    public List<KeyInfo> listKeys(String tenantId) {
        JsonNode data = exchange(client.get()
                .uri(uri -> uri.path("/api/v1/admin/app-keys")
                        .queryParam("tenantId", tenantId).queryParam("pageSize", 100).build()));
        List<KeyInfo> keys = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            keys.add(new KeyInfo(item.path("appKeyId").asText(), item.path("tenantId").asText(),
                    item.path("status").asInt(), String.valueOf(item.path("quota"))));
        }
        return keys;
    }

    private JsonNode post(String path, Object body) {
        return exchange(client.post().uri(path).body(body));
    }

    /** GET/POST/PUT 统一收发（RequestBodySpec 亦为 RequestHeadersSpec 子类） */
    private JsonNode exchange(RestClient.RequestHeadersSpec<?> spec) {
        try {
            return unwrap(spec.retrieve().body(JsonEnvelope.class));
        } catch (RestClientResponseException e) {
            log.error("platform call failed: HTTP {}", e.getStatusCode().value());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行平台调用失败");
        }
    }

    private JsonNode unwrap(JsonEnvelope envelope) {
        if (envelope == null || envelope.code() != 0) {
            String message = envelope == null ? "无响应" : envelope.message();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行平台返回错误: " + message);
        }
        return envelope.data();
    }

    private String text(JsonNode data, String field) {
        String value = data.path(field).asText(null);
        if (value == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行平台响应缺字段: " + field);
        }
        return value;
    }

    /** gateway ApiResponse 信封 */
    private record JsonEnvelope(int code, String message, JsonNode data) {
    }
}
