package com.skill.platform.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Artifact;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.mapper.ArtifactMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.infra.KvStore;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.ExecuteDtos.CancelResult;
import com.skill.platform.gateway.service.ExecuteDtos.ExecutionView;
import com.skill.platform.gateway.service.ExecutionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行查询与取消测试（§5.4 / TC-SCH-006/008、TC-CHN-001 终态同构）。
 */
@SpringBootTest
@ActiveProfiles("local")
class ExecutionQueryServiceTest {

    @Autowired
    private ExecutionQueryService queryService;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private ArtifactMapper artifactMapper;
    @Autowired
    private KvStore kv;

    @MockBean
    private BillingClient billingClient;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Execution seedExecution(String status, String holdId) {
        Execution execution = new Execution();
        execution.setTaskId("task_" + uniq());
        execution.setAppKeyId("sk-q-" + uniq());
        execution.setTenantId("tenant-" + uniq());
        execution.setSkillCode("demo-skill");
        execution.setSkillVersion("1.0.0");
        execution.setPackageSha256("a".repeat(64));
        execution.setContext("{\"session\":\"wb-123\"}");
        execution.setExpectedCount(2);
        execution.setOutputConfigSnapshot("{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}");
        execution.setPricingSnapshot("{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        execution.setHoldId(holdId);
        execution.setStatus(status);
        execution.setCallbackStatus("SENT");
        execution.setModelCalls(3);
        execution.setTokensUsed(4500);
        execution.setDurationMs(32000L);
        execution.setCreatedBy(execution.getAppKeyId());
        if (Execution.isTerminal(status)) {
            execution.setFinishedAt(java.time.LocalDateTime.now());
        }
        executionMapper.insert(execution);
        return execution;
    }

    private CallerContext callerOf(Execution execution) {
        return new CallerContext(execution.getAppKeyId(), execution.getTenantId());
    }

    // ------------------------------------------------------------------
    // 取消状态机
    // ------------------------------------------------------------------

    @Test
    void cancel_pending_releases_hold_and_returns_cancelled() {
        Execution execution = seedExecution(Execution.STATUS_PENDING, "hold_x1");
        CancelResult result = queryService.cancel(callerOf(execution), execution.getTaskId());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancelled()).isTrue();
        assertThat(executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, execution.getTaskId())).getStatus())
                .isEqualTo("CANCELLED");
        verify(billingClient).releaseByTask(execution.getTaskId());
    }

    @Test
    void cancel_running_marks_cancelling_without_refund() {
        Execution execution = seedExecution(Execution.STATUS_RUNNING, "hold_x2");
        CancelResult result = queryService.cancel(callerOf(execution), execution.getTaskId());

        assertThat(result.status()).isEqualTo("CANCELLING");
        verify(billingClient, never()).releaseByTask(any());
        // 幂等：再取消仍是 CANCELLING
        CancelResult again = queryService.cancel(callerOf(execution), execution.getTaskId());
        assertThat(again.status()).isEqualTo("CANCELLING");
    }

    @Test
    void cancel_terminal_idempotent_no_double_refund() {
        Execution execution = seedExecution(Execution.STATUS_SUCCEEDED, "hold_x3");
        CancelResult result = queryService.cancel(callerOf(execution), execution.getTaskId());

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.cancelled()).isFalse();
        verify(billingClient, never()).releaseByTask(any());
    }

    @Test
    void foreign_or_missing_task_404() {
        Execution execution = seedExecution(Execution.STATUS_PENDING, null);
        CallerContext foreign = new CallerContext("sk-other-" + uniq(), "tenant-other");
        assertThatThrownBy(() -> queryService.cancel(foreign, execution.getTaskId()))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> queryService.get(foreign, "task_ghost_" + uniq()))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // 查询：终态同构 / 进度 / billing 块
    // ------------------------------------------------------------------

    @Test
    void terminal_query_returns_full_result_body_isomorphic_with_callback() {
        Execution execution = seedExecution(Execution.STATUS_SUCCEEDED, "hold_settled_1");
        Artifact artifact = new Artifact();
        artifact.setArtifactId("art_" + uniq());
        artifact.setTaskId(execution.getTaskId());
        artifact.setTenantId(execution.getTenantId());
        artifact.setType("video");
        artifact.setOssKey(execution.getTenantId() + "/artifacts/" + execution.getTaskId() + "/out.mp4");
        artifact.setFileSize(15200000L);
        artifactMapper.insert(artifact);
        when(billingClient.settlementOf(execution.getTaskId()))
                .thenReturn(new BillingViews.SettlementView("hold_settled_1", "SETTLED", 20L));

        ExecutionView view = queryService.get(callerOf(execution), execution.getTaskId());

        // 与回调 body 同构的字段全集（§5.5）
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.expectedCount()).isEqualTo(2);
        assertThat(view.context().path("session").asText()).isEqualTo("wb-123");
        assertThat(view.modelCalls()).isEqualTo(3);
        assertThat(view.tokensUsed()).isEqualTo(4500);
        assertThat(view.durationMs()).isEqualTo(32000L);
        assertThat(view.timestamp()).isNotNull();
        assertThat(view.artifacts()).hasSize(1);
        assertThat(view.artifacts().get(0).url()).contains("presign=get");
        assertThat(view.artifacts().get(0).sizeBytes()).isEqualTo(15200000L);
        assertThat(view.billing().mode()).isEqualTo("PER_EXECUTION");
        assertThat(view.billing().pointsCharged()).isEqualTo(20L);
        assertThat(view.billing().settled()).isTrue();

        // 终态缓存生效：第二次查询命中缓存（billing client 只调一次）
        queryService.get(callerOf(execution), execution.getTaskId());
        verify(billingClient).settlementOf(execution.getTaskId());
    }

    @Test
    void terminal_query_with_released_hold_reports_zero_charge() {
        Execution execution = seedExecution(Execution.STATUS_FAILED, "hold_rel_1");
        execution.setErrorCode("TIMEOUT");
        execution.setErrorMessage("Skill execution exceeded 600s");
        executionMapper.updateById(execution);
        when(billingClient.settlementOf(execution.getTaskId()))
                .thenReturn(new BillingViews.SettlementView("hold_rel_1", "RELEASED", null));

        ExecutionView view = queryService.get(callerOf(execution), execution.getTaskId());

        assertThat(view.error().code()).isEqualTo("TIMEOUT");
        assertThat(view.billing().pointsCharged()).isZero();
        assertThat(view.billing().settled()).isTrue();
    }

    @Test
    void terminal_query_with_frozen_hold_reports_estimate_not_settled() {
        // settle RPC 双次失败降级时刻：settled=false + 预估值（TC-BIL-010 查询侧语义）
        Execution execution = seedExecution(Execution.STATUS_SUCCEEDED, "hold_frozen_1");
        when(billingClient.settlementOf(execution.getTaskId()))
                .thenReturn(new BillingViews.SettlementView("hold_frozen_1", "FROZEN", null));

        ExecutionView view = queryService.get(callerOf(execution), execution.getTaskId());

        assertThat(view.billing().settled()).isFalse();
        // 预估 = 单价 10 × expected 2
        assertThat(view.billing().pointsCharged()).isEqualTo(20L);
    }

    @Test
    void running_query_reports_progress_from_store() {
        Execution execution = seedExecution(Execution.STATUS_RUNNING, null);
        kv.set("task:progress:" + execution.getTaskId(), "37", Duration.ofMinutes(10));

        ExecutionView view = queryService.get(callerOf(execution), execution.getTaskId());

        assertThat(view.status()).isEqualTo("RUNNING");
        assertThat(view.progress()).isEqualTo(37);
        assertThat(view.artifacts()).isNull();
        assertThat(view.billing()).isNull();
    }

    @Test
    void progress_out_of_range_clamped() {
        Execution execution = seedExecution(Execution.STATUS_RUNNING, null);
        kv.set("task:progress:" + execution.getTaskId(), "150", Duration.ofMinutes(10));
        assertThat(queryService.get(callerOf(execution), execution.getTaskId()).progress()).isEqualTo(100);

        Execution another = seedExecution(Execution.STATUS_RUNNING, null);
        kv.set("task:progress:" + another.getTaskId(), "-3", Duration.ofMinutes(10));
        assertThat(queryService.get(callerOf(another), another.getTaskId()).progress()).isZero();
    }

    @Test
    void list_returns_tenant_scoped_lightweight_rows() {
        String tenant = "tenant-list-" + uniq();
        String appKey = "sk-list-" + uniq();
        for (int i = 0; i < 3; i++) {
            Execution execution = seedExecution(Execution.STATUS_SUCCEEDED, null);
            execution.setTenantId(tenant);
            execution.setAppKeyId(appKey);
            executionMapper.updateById(execution);
        }
        seedExecution(Execution.STATUS_SUCCEEDED, null); // 其他租户行，不应出现在结果里

        var page = queryService.list(new CallerContext(appKey, tenant), null, 1, 10);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).allSatisfy(row -> {
            assertThat(row.taskId()).startsWith("task_");
            assertThat(row.artifacts()).isNull();
            assertThat(row.billing()).isNull();
        });

        var runningOnly = queryService.list(new CallerContext(appKey, tenant),
                Execution.STATUS_RUNNING, 1, 10);
        assertThat(runningOnly.getTotal()).isZero();
    }

}
