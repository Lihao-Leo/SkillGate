package com.skill.platform.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警配置统一维护（管理后台「系统配置 → 飞书告警」）：读写 sys_config 四键
 * （alert.feishu-webhook-url / secret / running-seconds / tokens），并提供
 * 「发送测试消息」——服务端按当前已保存配置代发飞书文本消息，运营保存后
 * 立即可验证连通性（worker 侧 60s 拉取同一份配置热生效）。
 *
 * <p>飞书自定义机器人加签：sign = base64(hmac_sha256(key=f"{ts}\\n{secret}", msg=""))。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertConfigService {

    public static final String KEY_WEBHOOK = "alert.feishu-webhook-url";
    public static final String KEY_SECRET = "alert.feishu-secret";
    public static final String KEY_RUNNING_SECONDS = "alert.running-seconds";
    public static final String KEY_TOKENS = "alert.tokens";

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record AlertConfig(String webhookUrl, String secret, int runningSeconds, int tokensThreshold) {
    }

    /** 当前生效配置（sys_config 覆盖，留空 = 代码默认） */
    public AlertConfig get() {
        return new AlertConfig(
                str(KEY_WEBHOOK),
                str(KEY_SECRET),
                sysConfigService.intOf(KEY_RUNNING_SECONDS, 600),
                sysConfigService.intOf(KEY_TOKENS, 0));
    }

    /** 键值读取（null 归一为空串，便于判空） */
    private String str(String key) {
        String value = sysConfigService.get(key);
        return value == null ? "" : value;
    }

    /** 保存配置（worker 60s 内拉取热生效，无需重启） */
    public void save(AlertConfig config) {
        sysConfigService.put(KEY_WEBHOOK, nullSafe(config.webhookUrl()), "飞书机器人 webhook");
        sysConfigService.put(KEY_SECRET, nullSafe(config.secret()), "飞书机器人加签密钥");
        sysConfigService.put(KEY_RUNNING_SECONDS, String.valueOf(config.runningSeconds()),
                "RUNNING 长跑告警阈值（秒，0=关）");
        sysConfigService.put(KEY_TOKENS, String.valueOf(config.tokensThreshold()),
                "单任务 token 告警阈值（0=关）");
    }

    /**
     * 发送测试消息到当前已保存的 webhook。
     *
     * @return {ok, message, response} —— ok=false 时 message 含失败原因（前端直接展示）
     */
    public Map<String, Object> sendTest() {
        Map<String, Object> result = new LinkedHashMap<>();
        AlertConfig config = get();
        if (config.webhookUrl() == null || config.webhookUrl().isBlank()) {
            result.put("ok", false);
            result.put("message", "未配置 webhook 地址，请先填写并保存");
            return result;
        }
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", timestamp);
            if (notBlank(config.secret())) {
                payload.put("sign", feishuSign(timestamp, config.secret()));
            }
            payload.put("msg_type", "text");
            payload.put("content", Map.of("text",
                    "✅ SkillGate 告警配置测试消息\n通道连通成功，长跑/失败/token 超限告警将发送到本群。"));

            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    objectMapper.writeValueAsBytes(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            result.put("response", body.length() > 200 ? body.substring(0, 200) : body);
            if (response.statusCode() != 200) {
                result.put("ok", false);
                result.put("message", "飞书返回 HTTP " + response.statusCode());
                return result;
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            int code = node.has("code") ? node.path("code").asInt()
                    : node.path("StatusCode").asInt(-1);
            result.put("ok", code == 0);
            result.put("message", code == 0 ? "测试消息已送达飞书群"
                    : "飞书拒绝：" + node.path("msg").asText(node.path("errmsg").asText("未知错误")));
            return result;
        } catch (Exception sendFailure) {
            log.warn("alert config test send failed: {}", sendFailure.getMessage());
            result.put("ok", false);
            result.put("message", "发送失败：" + sendFailure.getMessage());
            return result;
        }
    }

    /** 飞书加签：HmacSHA256(key = "{ts}\\n{secret}", msg = "") → Base64 */
    static String feishuSign(String timestamp, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                (timestamp + "\n" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
