package com.skill.platform.gateway;

import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.infra.KvStore;
import com.skill.platform.gateway.service.ExecuteDtos.CancelResult;
import com.skill.platform.gateway.service.ExecutionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理台强制终止测试（全平台无租户过滤）：RUNNING → CANCELLING + Redis 加速标志；
 * PENDING → 直接取消；终态幂等；任务不存在 404。
 */
@SpringBootTest
@ActiveProfiles("local")
class ForceCancelAdminTest {

    @Autowired
    private ExecutionQueryService queryService;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private KvStore kv;

    private void seed(String taskId, String status, String holdId) {
        Execution e = new Execution();
        e.setTaskId(taskId);
        e.setClientRequestId("req-" + taskId);
        e.setAppKeyId("sk-fc-test");
        e.setTenantId("tenant-fc-" + UUID.randomUUID().toString().substring(0, 6));
        e.setSkillCode("fc-skill");
        e.setSkillVersion("1.0.0");
        e.setPackageSha256("a".repeat(64));
        e.setStatus(status);
        e.setHoldId(holdId);
        e.setCallbackStatus("NO_CALLBACK");
        e.setModelCalls(0);
        e.setTokensUsed(0);
        e.setCreatedBy("sk-fc-test");
        e.setStartedAt(status.equals("RUNNING") ? LocalDateTime.now() : null);
        executionMapper.insert(e);
    }

    private boolean flagSet(String taskId) {
        return kv.get("task:force-cancel:" + taskId) != null;
    }

    @Test
    void running_force_cancel_sets_cancelling_and_redis_flag() {
        String taskId = "fc-run-" + UUID.randomUUID().toString().substring(0, 8);
        seed(taskId, Execution.STATUS_RUNNING, "hold_" + taskId);

        CancelResult result = queryService.forceCancelForAdmin(taskId);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.status()).isEqualTo(Execution.STATUS_CANCELLING);
        assertThat(flagSet(taskId)).isTrue();
    }

    @Test
    void pending_force_cancel_directly_cancels_and_sets_flag() {
        String taskId = "fc-pend-" + UUID.randomUUID().toString().substring(0, 8);
        seed(taskId, Execution.STATUS_PENDING, null);

        CancelResult result = queryService.forceCancelForAdmin(taskId);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.status()).isEqualTo(Execution.STATUS_CANCELLED);
        assertThat(flagSet(taskId)).isTrue();
    }

    @Test
    void terminal_task_is_idempotent_without_flag() {
        String taskId = "fc-done-" + UUID.randomUUID().toString().substring(0, 8);
        seed(taskId, Execution.STATUS_SUCCEEDED, null);

        CancelResult result = queryService.forceCancelForAdmin(taskId);

        assertThat(result.cancelled()).isFalse();
        assertThat(result.status()).isEqualTo(Execution.STATUS_SUCCEEDED);
        assertThat(flagSet(taskId)).isFalse();
    }

    @Test
    void missing_task_throws_not_found() {
        assertThatThrownBy(() -> queryService.forceCancelForAdmin("task_ghost"))
                .isInstanceOf(com.skill.platform.gateway.common.BizException.class)
                .hasMessageContaining("任务不存在");
    }
}
