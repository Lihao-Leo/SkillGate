package com.skill.platform.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型网关 embeddings 测试（TC-KB-001）：alias 路由复用、input 校验（缺失/空/非字符串/超上限）、
 * 供应商模型名替换、鉴权与 chat 同语义。
 */
@SpringBootTest
@ActiveProfiles("local")
class ModelGatewayEmbeddingsTest {

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
    final AtomicReference<Integer> lastInputCount = new AtomicReference<>();

    private String uniq() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void startStub() throws Exception {
        lastAuth.set(null);
        lastModel.set(null);
        lastInputCount.set(null);
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode req = json.readTree(body);
            lastModel.set(req.path("model").asText());
            lastInputCount.set(req.path("input").isArray() ? req.path("input").size() : 1);
            var item = json.createObjectNode().put("index", 0);
            item.set("embedding", json.createArrayNode().add(0.1).add(0.2).add(0.3));
            var replyObj = json.createObjectNode().put("object", "list");
            replyObj.set("data", json.createArrayNode().add(item));
            replyObj.set("usage", json.createObjectNode().put("total_tokens", 7));
            byte[] reply = json.writeValueAsBytes(replyObj);
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
        ModelProvider existing = providerMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        if (existing != null) {
            providerMapper.deleteById(existing.getId());
        }
        ModelProvider provider = new ModelProvider();
        provider.setAlias(alias);
        provider.setProvider("openai");
        provider.setModelName("text-embedding-stub");
        provider.setEndpoint(endpoint);
        provider.setApiKeyCipher(cryptoService.encrypt("stub-embed-key"));
        provider.setFallbackAlias(fallback);
        provider.setMaxQps(10);
        provider.setStatus(status);
        providerMapper.insert(provider);
    }

    private String body(String alias, JsonNode input) {
        return json.createObjectNode().put("model", alias).set("input", input).toString();
    }

    @Test
    void authorize_semantics_match_chat_completions() {
        assertThat(gateway.authorize("Bearer dev-internal-token")).isTrue();
        assertThat(gateway.authorize("Bearer wrong-token")).isFalse();
        assertThat(gateway.authorize(null)).isFalse();
    }

    @Test
    void routes_by_alias_replaces_model_and_returns_vectors() throws Exception {
        String alias = "emb-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        ModelGatewayService.GatewayResult result = gateway.embeddings(
                body(alias, json.createArrayNode().add("文本A").add("文本B")));

        assertThat(result.status()).isEqualTo(200);
        JsonNode reply = json.readTree(result.body());
        assertThat(reply.path("object").asText()).isEqualTo("list");
        assertThat(reply.path("data").get(0).path("embedding").get(0).asDouble()).isEqualTo(0.1);
        assertThat(reply.path("usage").path("total_tokens").asInt()).isEqualTo(7);
        assertThat(lastAuth.get()).isEqualTo("Bearer stub-embed-key");
        assertThat(lastModel.get()).isEqualTo("text-embedding-stub");
        assertThat(lastInputCount.get()).isEqualTo(2);
    }

    @Test
    void single_string_input_is_accepted() throws Exception {
        String alias = "emb-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        ModelGatewayService.GatewayResult result = gateway.embeddings(
                json.createObjectNode().put("model", alias).put("input", "单串入参").toString());

        assertThat(result.status()).isEqualTo(200);
        assertThat(lastInputCount.get()).isEqualTo(1);
    }

    @Test
    void missing_empty_or_non_string_input_rejected() throws Exception {
        String alias = "emb-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        // 缺失
        assertThat(gateway.embeddings(json.createObjectNode().put("model", alias).toString())
                .status()).isEqualTo(400);
        // 空数组
        assertThat(gateway.embeddings(body(alias, json.createArrayNode())).status()).isEqualTo(400);
        // 非字符串元素
        assertThat(gateway.embeddings(body(alias, json.createArrayNode().add(123))).status())
                .isEqualTo(400);
        // 非字符串单值
        assertThat(gateway.embeddings(json.createObjectNode().put("model", alias)
                .put("input", 42).toString()).status()).isEqualTo(400);
    }

    @Test
    void over_limit_input_batch_rejected() throws Exception {
        String alias = "emb-" + uniq();
        upsertProvider(alias, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        var tooMany = json.createArrayNode();
        for (int i = 0; i < 65; i++) {
            tooMany.add("文本" + i);
        }
        ModelGatewayService.GatewayResult result = gateway.embeddings(body(alias, tooMany));

        assertThat(result.status()).isEqualTo(400);
        assertThat(new String(result.body())).contains("超上限");
    }

    @Test
    void unknown_or_disabled_alias_returns_openai_error() throws Exception {
        ModelGatewayService.GatewayResult result = gateway.embeddings(
                body("emb-nope-" + uniq(), json.createArrayNode().add("x")));

        assertThat(result.status()).isEqualTo(404);
        assertThat(new String(result.body())).contains("模型别名不存在或已禁用");
    }

    @Test
    void fallback_chain_routes_to_backup_on_unreachable_primary() throws Exception {
        String primary = "emb-primary-" + uniq();
        String backup = "emb-backup-" + uniq();
        upsertProvider(primary, "http://127.0.0.1:9", backup, 1);
        upsertProvider(backup, "http://127.0.0.1:" + stubPort + "/v1", null, 1);

        ModelGatewayService.GatewayResult result = gateway.embeddings(
                body(primary, json.createArrayNode().add("x")));

        assertThat(result.status()).isEqualTo(200);
        assertThat(lastModel.get()).isEqualTo("text-embedding-stub");
    }
}
