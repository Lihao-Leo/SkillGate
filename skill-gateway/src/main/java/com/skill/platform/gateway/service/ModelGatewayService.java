package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.dal.entity.ModelProvider;
import com.skill.platform.gateway.dal.mapper.ModelProviderMapper;
import com.skill.platform.gateway.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;

/**
 * 模型网关（§4.5）：OpenAI 兼容转发。后管 model_provider 表即唯一路由配置——
 * 按请求体 model=alias 实时查表（含 API Key AES 解密、fallback_alias 回退链），
 * 配置即生效，无外部网关组件（LiteLLM 不再需要）。
 *
 * <p>鉴权：仅限沙箱执行流量（Bearer {internal-token}-task-{taskId}，worker 注入）。
 * 计量：usage 由技能侧 toolset 上报（点数计费口径与 LiteLLM 无关）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelGatewayService {

    private final ModelProviderMapper modelProviderMapper;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @Value("${skill-platform.model-gateway.internal-token:${INTERNAL_TOKEN:dev-internal-token}}")
    private String internalToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record GatewayResult(int status, byte[] body, String contentType) {
    }

    public boolean authorize(String authorization) {
        return authorization != null && authorization.startsWith("Bearer " + internalToken);
    }

    /** 同步转发（含流式响应体透传——由调用方写回输出流时逐块 flush） */
    public GatewayResult chat(String rawBody) throws IOException, InterruptedException {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        } catch (IOException e) {
            return error(400, "invalid_request_error", "请求体不是合法 JSON");
        }
        String alias = root.path("model").asText(null);
        if (alias == null || alias.isBlank()) {
            return error(400, "invalid_request_error", "model 不能为空（填后管配置的能力别名）");
        }
        boolean stream = root.path("stream").asBoolean(false);

        String currentAlias = alias;
        var visited = new HashSet<String>();
        int attempts = 0;
        while (currentAlias != null && visited.add(currentAlias) && attempts < 4) {
            attempts++;
            ModelProvider provider = modelProviderMapper.selectOne(
                    new LambdaQueryWrapper<ModelProvider>()
                            .eq(ModelProvider::getAlias, currentAlias).last("LIMIT 1"));
            if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
                return error(404, "invalid_request_error", "模型别名不存在或已禁用: " + currentAlias);
            }
            String apiKey;
            try {
                apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
            } catch (Exception e) {
                log.warn("model gateway api key decrypt failed: alias={}", currentAlias);
                return error(500, "gateway_error", "模型密钥解密失败（检查 CRYPTO_KEY 一致性）");
            }
            String body = withModel(root, provider.getModelName());
            String url = chatCompletionsUrl(provider.getEndpoint());
            try {
                HttpResponse<java.io.InputStream> upstream = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(580))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKey)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes()))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (upstream.statusCode() >= 500) {
                    upstream.body().close();
                    log.warn("model gateway upstream 5xx, try fallback: alias={} -> {}",
                            currentAlias, provider.getFallbackAlias());
                    currentAlias = provider.getFallbackAlias();
                    continue;
                }
                byte[] bytes = upstream.body().readAllBytes();
                return new GatewayResult(upstream.statusCode(), bytes,
                        upstream.headers().firstValue("Content-Type")
                                .orElse(stream ? "text/event-stream" : "application/json"));
            } catch (IOException | InterruptedException connectError) {
                if (connectError instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("model gateway upstream unreachable: alias={} -> fallback={}",
                        currentAlias, provider.getFallbackAlias());
                currentAlias = provider.getFallbackAlias();
            }
        }
        return error(502, "gateway_error", "所有模型路由（含回退链）均不可用");
    }

    /** 请求体 model 字段替换为供应商侧模型名（其余参数原样透传） */
    private String withModel(JsonNode root, String modelName) {
        var copy = root.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("model", modelName);
        return copy.toString();
    }

    /** endpoint 约定填到 /v1（或完整路径），此处拼接 /chat/completions */
    static String chatCompletionsUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static GatewayResult error(int status, String type, String message) {
        String body = "{\"error\":{\"message\":\"" + message.replace("\"", "'")
                + "\",\"type\":\"" + type + "\"}}";
        return new GatewayResult(status, body.getBytes(), "application/json");
    }
}
