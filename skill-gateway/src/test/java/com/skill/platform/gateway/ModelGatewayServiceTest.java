package com.skill.platform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.dal.entity.ModelProvider;
import com.skill.platform.gateway.dal.mapper.ModelProviderMapper;
import com.skill.platform.gateway.security.CryptoService;
import com.skill.platform.gateway.service.ModelGatewayService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型网关测试（§4.5）：alias 实时路由、供应商侧模型名替换、密钥解密转发、
 * fallback 链、OpenAI 风格错误。
 */
@SpringBootTest
@ActiveProfiles("local")
class ModelGatewayServiceTest {

    @Autowired
    private ModelGatewayService gateway;
    @Autowired
    private ModelProviderMapper providerMapper;
    @Autowired
    private CryptoService cryptoService;

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer stub;
    private int stubPort;
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    final AtomicReference<String> lastModel = new AtomicReference<>();
    final AtomicInteger callCount = new AtomicInteger();

    private String uniq() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void startStub() throws Exception {
        lastAuth.set(null);
        lastModel.set(null);
        callCount.set(0);
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                lastModel.set(json.readTree(body).path("model").asText());
            } catch (Exception ignored) {
            }
            callCount.incrementAndGet();
            byte[] reply = json.writeValueAsBytes(json.createObjectNode()
                    .put("id", "chatcmpl-stub")
                    .set("choices", json.createArrayNode().add(json.createObjectNode()
                            .set("message", json.createObjectNode()
                                    .put("role", "assistant").put("content", "模型网关打通")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private void upsertProvider(String alias, String endpoint, String fallback, int status) {
        ModelProvider existing = providerMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        if (existing != null) {
            providerMapper.deleteById(existing.getId());
        }
        ModelProvider provider = new ModelProvider();
        provider.setAlias(alias);
        provider.setProvider("openai");
        provider.setModelName("gpt-4o-mini");
        provider.setEndpoint(endpoint);
        provider.setApiKeyCipher(cryptoService.encrypt("stub-provider-key"));
        provider.setFallbackAlias(fallback);
        provider.setMaxQps(10);
        provider.setStatus(status);
        providerMapper.insert(provider);
    }

    @Test
    void routes_by_alias_replaces_model_and_forwards_key() throws Exception {
        String alias = "llm-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        String body = json.createObjectNode()
                .put("model", alias)
                .set("messages", json.createArrayNode().add(json.createObjectNode()
                        .put("role", "user").put("content", "hi"))).toString();

        ModelGatewayService.GatewayResult result = gateway.chat(body);
        assertThat(result.status()).isEqualTo(200);
        JsonNode reply = json.readTree(result.body());
        assertThat(reply.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("模型网关打通");
        assertThat(lastAuth.get()).isEqualTo("Bearer stub-provider-key");
        assertThat(lastModel.get()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void unknown_or_disabled_alias_returns_openai_error() throws Exception {
        String body = json.createObjectNode().put("model", "nope-" + uniq()).toString();
        ModelGatewayService.GatewayResult result = gateway.chat(body);
        assertThat(result.status()).isEqualTo(404);
        assertThat(new String(result.body())).contains("模型别名不存在或已禁用");

        String alias = "disabled-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 0);
        assertThat(gateway.chat(json.createObjectNode().put("model", alias).toString())
                .status()).isEqualTo(404);
    }

    @Test
    void fallback_chain_routes_to_backup_on_upstream_500() throws Exception {
        String primary = "primary-" + uniq();
        String backup = "backup-" + uniq();
        // 主路由指向不存在端口（不可达）→ 回退到 stub
        upsertProvider(primary, "http://127.0.0.1:9", backup, 1);
        upsertProvider(backup, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        String body = json.createObjectNode().put("model", primary).toString();
        ModelGatewayService.GatewayResult result = gateway.chat(body);
        assertThat(result.status()).isEqualTo(200);
        assertThat(lastModel.get()).isEqualTo("gpt-4o-mini");
    }
}
