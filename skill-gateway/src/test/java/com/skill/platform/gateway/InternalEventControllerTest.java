package com.skill.platform.gateway;

import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部事件上报端点测试（TC-TR-002）：token 鉴权、批量写入 accepted=N、超限/缺参整批 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class InternalEventControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ExecutionEventMapper executionEventMapper;

    private String body(String taskId, String stage) {
        return """
                {"events":[{"taskId":"%s","tenantId":"acme","stage":"%s","status":"OK",
                "service":"worker","durationMs":12,"detail":{"job":"j1"},"traceId":"abc",
                "occurredAt":"2026-10-31T18:00:00.123"}]}
                """.formatted(taskId, stage);
    }

    @Test
    void wrong_or_missing_token_rejected_401() throws Exception {
        mockMvc.perform(post("/internal/execution-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t-auth-1", "STAGE")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/execution-events")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t-auth-2", "STAGE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void valid_token_batch_writes_accepted_and_persists() throws Exception {
        String taskId = "t-batch-" + System.nanoTime();

        mockMvc.perform(post("/internal/execution-events")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskId, "CLAIM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        List<ExecutionEvent> rows = executionEventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExecutionEvent>()
                        .eq(ExecutionEvent::getTaskId, taskId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo("CLAIM");
        assertThat(rows.get(0).getSeq()).isEqualTo(1);
        assertThat(rows.get(0).getDetail()).contains("job");
        assertThat(rows.get(0).getTraceId()).isEqualTo("abc");
    }

    @Test
    void over_limit_batch_rejected_40001() throws Exception {
        StringBuilder events = new StringBuilder();
        IntStream.range(0, 101).forEach(i -> events.append(
                "{\"taskId\":\"t-over\",\"tenantId\":\"acme\",\"stage\":\"STAGE\",\"status\":\"OK\"},"));
        String oversized = "{\"events\":[" + events.substring(0, events.length() - 1) + "]}";

        mockMvc.perform(post("/internal/execution-events")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void missing_task_id_rejected_40001() throws Exception {
        mockMvc.perform(post("/internal/execution-events")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[{\"tenantId\":\"acme\",\"stage\":\"STAGE\",\"status\":\"OK\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
