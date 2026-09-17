package com.skill.platform.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部 API 契约测试：X-Internal-Token 认证 + freeze/settle 主链路 + 40201 映射 HTTP 402。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class InternalApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void missing_internal_token_rejected_40101() throws Exception {
        mockMvc.perform(get("/internal/billing/balance").param("appKeyId", "sk-x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void invalid_internal_token_rejected() throws Exception {
        mockMvc.perform(get("/internal/billing/balance").param("appKeyId", "sk-x")
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void freeze_insufficient_maps_to_http_402() throws Exception {
        String appKeyId = "sk-api-" + uniq();
        mockMvc.perform(post("/internal/billing/accounts")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKeyId\":\"" + appKeyId + "\",\"tenantId\":\"tenant-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String body = "{\"taskId\":\"task-api-%s\",\"appKeyId\":\"%s\",\"amount\":100}".formatted(uniq(), appKeyId);
        mockMvc.perform(post("/internal/billing/freeze")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(40201));
    }

    @Test
    void freeze_then_settle_full_lifecycle() throws Exception {
        String appKeyId = "sk-api-" + uniq();
        String taskId = "task-api-" + uniq();
        postJson("/internal/billing/accounts", "{\"appKeyId\":\"%s\",\"tenantId\":\"t\"}".formatted(appKeyId));
        postJson("/internal/billing/recharge",
                "{\"appKeyId\":\"%s\",\"orderNo\":\"ord-%s\",\"points\":1000}".formatted(appKeyId, uniq()));

        MvcResult freezeResult = postJson("/internal/billing/freeze",
                "{\"taskId\":\"%s\",\"appKeyId\":\"%s\",\"amount\":300}".formatted(taskId, appKeyId));
        JsonNode freeze = objectMapper.readTree(freezeResult.getResponse().getContentAsString());
        String holdId = freeze.at("/data/holdId").asText();
        assertThat(holdId).startsWith("hold_");

        postJson("/internal/billing/settle",
                "{\"taskId\":\"%s\",\"actualPoints\":120}".formatted(taskId));

        mockMvc.perform(get("/internal/billing/balance").param("appKeyId", appKeyId)
                        .header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(880))
                .andExpect(jsonPath("$.data.frozen").value(0));

        mockMvc.perform(get("/internal/billing/holds").param("taskId", taskId)
                        .header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SETTLED"))
                .andExpect(jsonPath("$.data.settledAmount").value(120));
    }

    @Test
    void validation_failure_maps_to_40001() throws Exception {
        mockMvc.perform(post("/internal/billing/freeze")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"\",\"appKeyId\":\"sk-x\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }
}
