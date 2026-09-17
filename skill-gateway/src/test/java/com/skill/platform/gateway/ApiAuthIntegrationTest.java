package com.skill.platform.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillVersion;
import com.skill.platform.gateway.dal.mapper.AppKeyMapper;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.dal.mapper.SkillVersionMapper;
import com.skill.platform.gateway.security.CryptoService;
import com.skill.platform.gateway.security.HmacVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端鉴权集成测试（TC-EXE-002）：HMAC 全链 + admin token + telemetry token。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ApiAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AppKeyMapper appKeyMapper;
    @Autowired
    private SkillMapper skillMapper;
    @Autowired
    private com.skill.platform.gateway.infra.ObjectStorage objectStorage;
    @Autowired
    private SkillVersionMapper skillVersionMapper;
    @Autowired
    private CryptoService cryptoService;

    @MockBean
    private BillingClient billingClient;
    @MockBean
    private com.skill.platform.gateway.infra.ExecuteMessageProducer messageProducer;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record SeededKey(String appKeyId, String secret, String tenantId) {
    }

    private SeededKey seedKey() {
        String appKeyId = "sk-it-" + uniq();
        String secret = "sk-secret-" + uniq();
        AppKey appKey = new AppKey();
        appKey.setAppKeyId(appKeyId);
        appKey.setTenantId("tenant-" + uniq());
        appKey.setSecretCipher(cryptoService.encrypt(secret));
        appKey.setStatus(1);
        appKeyMapper.insert(appKey);
        return new SeededKey(appKeyId, secret, appKey.getTenantId());
    }
    private void seedSkill(SeededKey key, String skillCode) {
        Skill skill = new Skill();
        skill.setTenantId(key.tenantId());
        skill.setSkillCode(skillCode);
        skill.setName(skillCode);
        skill.setVisibility("PRIVATE");
        skill.setOutputConfig("{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}");
        skill.setPricingConfig("{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        skill.setDefaultVersion("1.0.0");
        skill.setStatus(1);
        skill.setCreatedBy(key.appKeyId());
        skillMapper.insert(skill);
        SkillVersion version = new SkillVersion();
        version.setSkillId(skill.getId());
        version.setVersion("1.0.0");
        version.setOssKey("skill/%s/%s/1.0.0/skill.zip".formatted(key.tenantId(), skillCode));
        version.setPackageSha256("b".repeat(64));
        version.setPackageSize(10L);
        version.setStatus(SkillVersion.STATUS_PUBLISHED);
        version.setUploadedBy(key.appKeyId());
        skillVersionMapper.insert(version);
        // 受理第零关校验包存在：种子版本一并放置包对象
        objectStorage.put(version.getOssKey(), "zip-bytes".getBytes(), "application/zip");
    }

    private MockHttpServletRequestBuilder signed(SeededKey key, String method, String pathWithQuery,
                                                 String body) {
        long timestamp = System.currentTimeMillis();
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String signature = HmacVerifier.signature(key.secret(), key.appKeyId(), timestamp,
                method, pathWithQuery, bodyBytes);
        MockHttpServletRequestBuilder builder = "GET".equals(method) ? get(pathWithQuery)
                : post(pathWithQuery).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body);
        return builder
                .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                .header(HmacVerifier.HEADER_TIMESTAMP, timestamp)
                .header(HmacVerifier.HEADER_SIGNATURE, signature);
    }

    @Test
    void execute_with_valid_signature_accepted() throws Exception {
        SeededKey key = seedKey();
        String skillCode = "it-" + uniq();
        seedSkill(key, skillCode);
        when(billingClient.freeze(any(), eq(key.appKeyId()), anyLong()))
                .thenReturn(new BillingViews.FreezeResult("hold_it", 10, false));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "skillCode", skillCode,
                "instructions", "测试指令"));
        mockMvc.perform(signed(key, "POST", "/api/v1/execute", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").exists())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void missing_or_bad_signature_rejected_40101() throws Exception {
        SeededKey key = seedKey();
        String body = "{\"skillCode\":\"x\"}";

        // 缺 Header
        mockMvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 签名错误（TC-EXE-002）
        long ts = System.currentTimeMillis();
        mockMvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts)
                        .header(HmacVerifier.HEADER_SIGNATURE, "deadbeef"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 时间戳超窗（重放防护）
        String staleSig = HmacVerifier.signature(key.secret(), key.appKeyId(),
                ts - 10 * 60_000L, "POST", "/api/v1/execute",
                body.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts - 10 * 60_000L)
                        .header(HmacVerifier.HEADER_SIGNATURE, staleSig))
                .andExpect(status().isUnauthorized());

        // 无效 AppKey
        mockMvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacVerifier.HEADER_APP_KEY, "sk-ghost")
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts)
                        .header(HmacVerifier.HEADER_SIGNATURE, "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_endpoints_require_admin_token() throws Exception {
        SeededKey key = seedKey();
        mockMvc.perform(post("/api/v1/admin/credits/recharge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKeyId\":\"%s\",\"points\":10,\"orderNo\":\"o1\"}"
                                .formatted(key.appKeyId())))
                .andExpect(status().isUnauthorized());

        when(billingClient.recharge(any(), any(), anyLong(), any()))
                .thenReturn(new BillingViews.RechargeResult("tx_1", key.appKeyId(), 10, 100, 0, false));
        mockMvc.perform(post("/api/v1/admin/credits/recharge")
                        .header("X-Admin-Token", "change-me-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKeyId\":\"%s\",\"points\":10,\"orderNo\":\"o1\"}"
                                .formatted(key.appKeyId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(100))
                .andExpect(jsonPath("$.data.alreadyCredited").value(false));

        // 签发 AppKey：secret 明文一次性返回 + billing 开户（void mock 默认 no-op）
        mockMvc.perform(post("/api/v1/admin/app-keys")
                        .header("X-Admin-Token", "change-me-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appKeyId").exists())
                .andExpect(jsonPath("$.data.appSecret").value(
                        org.hamcrest.Matchers.startsWith("sk-secret-")));
    }

    @Test
    void admin_key_lifecycle_list_reset_and_disable() throws Exception {
        SeededKey key = seedKey();

        // 列表（完整 secret 永不出库）
        mockMvc.perform(get("/api/v1/admin/app-keys")
                        .param("tenantId", key.tenantId())
                        .header("X-Admin-Token", "change-me-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].appKeyId").value(key.appKeyId()))
                .andExpect(jsonPath("$.data.items[0].status").value(1));

        // 重置=轮换：旧 secret 立即失效，新 secret 可用
        String resetBody = mockMvc.perform(post("/api/v1/admin/app-keys/{id}/reset-secret", key.appKeyId())
                        .header("X-Admin-Token", "change-me-admin-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newSecret = objectMapper.readTree(resetBody).at("/data/appSecret").asText();

        String healthPath = "/api/v1/account/balance";
        long ts = System.currentTimeMillis();
        String oldSig = HmacVerifier.signature(key.secret(), key.appKeyId(), ts, "GET", healthPath, new byte[0]);
        mockMvc.perform(get(healthPath)
                        .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts)
                        .header(HmacVerifier.HEADER_SIGNATURE, oldSig))
                .andExpect(status().isUnauthorized());

        when(billingClient.balance(key.appKeyId()))
                .thenReturn(new BillingViews.BalanceView(key.appKeyId(), 0, 0));
        long ts2 = System.currentTimeMillis();
        String newSig = HmacVerifier.signature(newSecret, key.appKeyId(), ts2, "GET", healthPath, new byte[0]);
        mockMvc.perform(get(healthPath)
                        .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts2)
                        .header(HmacVerifier.HEADER_SIGNATURE, newSig))
                .andExpect(status().isOk());

        // 禁用 → 40101
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/app-keys/{id}/status", key.appKeyId())
                        .header("X-Admin-Token", "change-me-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));

        long ts3 = System.currentTimeMillis();
        String sig3 = HmacVerifier.signature(newSecret, key.appKeyId(), ts3, "GET", healthPath, new byte[0]);
        mockMvc.perform(get(healthPath)
                        .header(HmacVerifier.HEADER_APP_KEY, key.appKeyId())
                        .header(HmacVerifier.HEADER_TIMESTAMP, ts3)
                        .header(HmacVerifier.HEADER_SIGNATURE, sig3))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void telemetry_requires_skill_token_and_logs_io() throws Exception {
        // 无效令牌 → 40101
        mockMvc.perform(post("/api/v1/telemetry/skill-io")
                        .header("X-Skill-Token", "skt-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isUnauthorized());

        // 有效令牌 → 入库（TC-FRE-003）
        SeededKey key = seedKey();
        Skill skill = new Skill();
        skill.setTenantId(key.tenantId());
        skill.setSkillCode("free-" + uniq());
        skill.setName("free");
        skill.setVisibility("PUBLIC");
        skill.setPricingConfig("{\"mode\":\"FREE\"}");
        skill.setTelemetryToken("skt-" + uniq());
        skill.setStatus(1);
        skill.setCreatedBy(key.appKeyId());
        skillMapper.insert(skill);

        mockMvc.perform(post("/api/v1/telemetry/skill-io")
                        .header("X-Skill-Token", skill.getTelemetryToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "status", "SUCCEEDED",
                                "durationMs", 1200,
                                "callerHint", "workbuddy"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
