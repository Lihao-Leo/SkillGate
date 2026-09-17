package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Artifact;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.mapper.ArtifactMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.infra.KvStore;
import com.skill.platform.gateway.infra.ObjectStorage;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.ExecuteDtos.CancelResult;
import com.skill.platform.gateway.service.ExecuteDtos.ExecutionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 执行查询与取消（§5.4）。
 *
 * <p>轮询契约：终态查询响应 = 完整结果体，与回调 body 同构（status/artifacts/billing/context/error）；
 * 先落产物与结算再置终态，不存在「完成但无结果」竞态。终态响应缓存 TTL 2s 抗轮询洪峰。
 *
 * <p>取消状态机：PENDING → CANCELLED（直接退款）；RUNNING → CANCELLING（worker 收殓）；
 * 已终态幂等返回（不重复结算/退款）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionQueryService {

    private static final Duration ARTIFACT_URL_TTL = Duration.ofHours(24);
    private static final Duration TERMINAL_CACHE_TTL = Duration.ofSeconds(2);

    private final ExecutionMapper executionMapper;
    private final ArtifactMapper artifactMapper;
    private final BillingClient billingClient;
    private final ObjectStorage objectStorage;
    private final KvStore kv;

    public ExecutionView get(CallerContext caller, String taskId) {
        Execution execution = loadOwned(caller, taskId);
        if (Execution.isTerminal(execution.getStatus())) {
            return terminalView(execution);
        }
        return runningView(execution);
    }

    /**
     * 最近任务列表（§10.1 执行监控）：本租户维度分页，轻量行（不签产物 URL）。
     */
    public Page<ExecutionView> list(CallerContext caller, String status, int pageNo, int pageSize) {
        Page<Execution> page = executionMapper.selectPage(
                new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100)),
                new LambdaQueryWrapper<Execution>()
                        .eq(Execution::getTenantId, caller.tenantId())
                        .eq(status != null && !status.isBlank(), Execution::getStatus, status)
                        .orderByDesc(Execution::getId));
        List<ExecutionView> rows = page.getRecords().stream().map(e -> new ExecutionView(
                        e.getTaskId(), e.getSkillCode(), e.getSkillVersion(), e.getStatus(),
                        e.getProgress(), null, null, e.getExpectedCount(), null, null, null,
                        null, null, null, e.getErrorCode() == null ? null
                                : new ExecutionView.ErrorBlock(e.getErrorCode(), null),
                        e.getCreatedAt() == null ? null : e.getCreatedAt().toString())).toList();
        Page<ExecutionView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(rows);
        return result;
    }

    public CancelResult cancel(CallerContext caller, String taskId) {
        Execution execution = loadOwned(caller, taskId);
        switch (execution.getStatus()) {
            case Execution.STATUS_PENDING -> {
                return cancelPending(execution, caller);
            }
            case Execution.STATUS_RUNNING, Execution.STATUS_CANCELLING -> {
                executionMapper.markCancelling(taskId);
                return new CancelResult(taskId, Execution.STATUS_CANCELLING, true);
            }
            default -> {
                // 已终态：幂等返回当前终态，不重复结算/退款（TC-SCH-008）
                return new CancelResult(taskId, execution.getStatus(), false);
            }
        }
    }

    private CancelResult cancelPending(Execution execution, CallerContext caller) {
        int updated = executionMapper.cancelPending(execution.getTaskId());
        if (updated == 0) {
            // 竞态：刚被 worker 接手 → 按最新状态重走分支
            return cancel(caller, execution.getTaskId());
        }
        releaseHold(execution);
        return new CancelResult(execution.getTaskId(), Execution.STATUS_CANCELLED, true);
    }

    private void releaseHold(Execution execution) {
        if (execution.getHoldId() == null) {
            return;
        }
        try {
            billingClient.releaseByTask(execution.getTaskId());
        } catch (Exception e) {
            // 释放失败由对账兜底（FROZEN>24h 且任务终态 → 补结算/释放）
            log.error("release on cancel failed, reconcile will cover: taskId={}", execution.getTaskId(), e);
        }
    }

    private ExecutionView runningView(Execution execution) {
        return new ExecutionView(
                execution.getTaskId(),
                execution.getSkillCode(),
                execution.getSkillVersion(),
                execution.getStatus(),
                progressOf(execution),
                execution.getCallbackStatus(),
                execution.getCallbackUrl(),
                execution.getExpectedCount(),
                contextOf(execution),
                null, null, null, null, null, null, null);
    }

    private ExecutionView terminalView(Execution execution) {
        String cacheKey = "poll:terminal:" + execution.getTaskId();
        String cached = kv.get(cacheKey);
        if (cached != null) {
            try {
                return JsonCodec.readUnchecked(cached, ExecutionView.class);
            } catch (Exception ignore) {
                // 缓存损坏直接重建
            }
        }
        List<ExecutionView.ArtifactView> artifacts = artifactMapper.selectList(
                        new LambdaQueryWrapper<Artifact>().eq(Artifact::getTaskId, execution.getTaskId()))
                .stream()
                .map(a -> new ExecutionView.ArtifactView(
                        a.getArtifactId(),
                        a.getType(),
                        objectStorage.presignGet(a.getOssKey(), ARTIFACT_URL_TTL).url(),
                        a.getFileSize()))
                .toList();
        ExecutionView view = new ExecutionView(
                execution.getTaskId(),
                execution.getSkillCode(),
                execution.getSkillVersion(),
                execution.getStatus(),
                execution.getProgress(),
                execution.getCallbackStatus(),
                execution.getCallbackUrl(),
                execution.getExpectedCount(),
                contextOf(execution),
                artifacts,
                billingBlockOf(execution),
                execution.getModelCalls(),
                execution.getTokensUsed(),
                execution.getDurationMs(),
                errorOf(execution),
                execution.getFinishedAt() == null ? null
                        : execution.getFinishedAt().toString());
        kv.set(cacheKey, JsonCodec.write(view), TERMINAL_CACHE_TTL);
        return view;
    }

    /**
     * billing 块（与回调同构）：settled=true 为最终扣点；false 为预估（settle RPC 双次失败降级时刻）。
     */
    private ExecutionView.BillingBlock billingBlockOf(Execution execution) {
        JsonNode pricing = JsonCodec.treeOrEmpty(execution.getPricingSnapshot());
        String mode = pricing.path("mode").asText("PER_EXECUTION");
        if (execution.getHoldId() == null) {
            return new ExecutionView.BillingBlock(mode, 0L, null, true);
        }
        try {
            BillingViews.SettlementView settlement = billingClient.settlementOf(execution.getTaskId());
            if (settlement == null) {
                return new ExecutionView.BillingBlock(mode, 0L, execution.getHoldId(), true);
            }
            return switch (settlement.status() == null ? "" : settlement.status()) {
                case "SETTLED" -> new ExecutionView.BillingBlock(mode,
                        settlement.settledAmount() == null ? 0 : settlement.settledAmount(),
                        settlement.holdId(), true);
                case "RELEASED" -> new ExecutionView.BillingBlock(mode, 0L, settlement.holdId(), true);
                // FROZEN：结算在途（RPC 降级、对账未跑）——pointsCharged 为预估，终值以 transactions 为准
                default -> new ExecutionView.BillingBlock(mode, estimateCharged(pricing, execution),
                        settlement.holdId(), false);
            };
        } catch (Exception e) {
            log.warn("query settlement failed, fallback to estimate: taskId={}", execution.getTaskId(), e);
            return new ExecutionView.BillingBlock(mode, estimateCharged(pricing, execution),
                    execution.getHoldId(), false);
        }
    }

    private long estimateCharged(JsonNode pricing, Execution execution) {
        if ("METERED".equals(pricing.path("mode").asText())) {
            return pricing.path("capPoints").asLong();
        }
        long unit = pricing.path("points").asLong();
        int expected = execution.getExpectedCount() == null ? 1 : execution.getExpectedCount();
        return unit * expected;
    }

    private Integer progressOf(Execution execution) {
        // worker 经 Redis task:progress:{taskId} 增量上报（§4.5），miss 回源 DB
        String progress = kv.get("task:progress:" + execution.getTaskId());
        if (progress != null) {
            try {
                return Math.max(0, Math.min(100, Integer.parseInt(progress)));
            } catch (NumberFormatException ignore) {
                // 非法进度值回源 DB
            }
        }
        return execution.getProgress();
    }

    private JsonNode contextOf(Execution execution) {
        if (execution.getContext() == null || execution.getContext().isBlank()) {
            return null;
        }
        try {
            return JsonCodec.tree(execution.getContext());
        } catch (Exception e) {
            return null;
        }
    }

    private ExecutionView.ErrorBlock errorOf(Execution execution) {
        if (execution.getErrorCode() == null) {
            return null;
        }
        return new ExecutionView.ErrorBlock(execution.getErrorCode(), execution.getErrorMessage());
    }

    private Execution loadOwned(CallerContext caller, String taskId) {
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, taskId)
                .last("LIMIT 1"));
        // 不存在或跨租户统一 40401，不泄露存在性
        if (execution == null || !execution.getAppKeyId().equals(caller.appKeyId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在: " + taskId);
        }
        return execution;
    }
}
