package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.infra.CallbackRetryProducer;
import com.skill.platform.gateway.security.CallerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常处理台（§10.1 执行监控）：死信回调重发 / 放弃。
 * 重发 = 网关按回调契约重建 body 投 skill-callback-retry（worker 消费补投，attempt+1）。
 */
@Service
@RequiredArgsConstructor
public class AdminOpsService {

    private final ExecutionMapper executionMapper;
    private final ExecutionQueryService executionQueryService;
    private final CallbackRetryProducer callbackRetryProducer;
    private final ExecutionEventMapper executionEventMapper;
    private final ObjectMapper objectMapper;

    /**
     * 任务执行事件链（技术方案 v2.0 §1.6）：按 seq 升序返回，detail 已解析为 JSON 对象。
     */
    public List<Map<String, Object>> executionEvents(String taskId) {
        List<ExecutionEvent> events = executionEventMapper.selectList(
                new LambdaQueryWrapper<ExecutionEvent>()
                        .eq(ExecutionEvent::getTaskId, taskId)
                        .orderByAsc(ExecutionEvent::getSeq));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExecutionEvent e : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", e.getTaskId());
            row.put("seq", e.getSeq());
            row.put("stage", e.getStage());
            row.put("status", e.getStatus());
            row.put("service", e.getService());
            row.put("durationMs", e.getDurationMs());
            row.put("traceId", e.getTraceId());
            row.put("occurredAt", e.getOccurredAt() == null ? null : e.getOccurredAt().toString());
            row.put("detail", parseDetail(e.getDetail()));
            rows.add(row);
        }
        return rows;
    }

    /** detail 字符串解析为对象；非 JSON 原样返回字符串 */
    private Object parseDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(detail);
        } catch (Exception parseFailure) {
            return detail;
        }
    }

    /** 最近任务列表（全平台，轻量行）——後管执行监控任务列表；筛选 + 耗时/token 排序 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> listExecutions(
            int hours, String status, String skillCode, String tenantId, boolean onlyFailed,
            String sortBy, int pageNo, int pageSize) {
        var since = java.time.LocalDateTime.now().minusHours(Math.max(hours, 1));
        // 条件布尔不短路实参求值：先归一化，避免 null.strip() NPE
        String skill = skillCode == null ? "" : skillCode.strip();
        String tenant = tenantId == null ? "" : tenantId.strip();
        // 排序白名单（防注入）：耗时/模型 token 降序支持「最耗时/最贵任务」排序诉求
        String sortKey = sortBy == null ? "" : sortBy.strip().toLowerCase();
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<Execution, ?> sortColumn =
                "duration".equals(sortKey) ? Execution::getDurationMs
                : "tokens".equals(sortKey) ? Execution::getTokensUsed
                : Execution::getId;
        var page = executionMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100)),
                new LambdaQueryWrapper<Execution>()
                        .ge(Execution::getCreatedAt, since)
                        .eq(onlyFailed, Execution::getStatus, "FAILED")
                        .eq(!onlyFailed && status != null && !status.isBlank(),
                                Execution::getStatus, status)
                        .like(!skill.isEmpty(), Execution::getSkillCode, skill)
                        .eq(!tenant.isEmpty(), Execution::getTenantId, tenant)
                        .orderByDesc(sortColumn)
                        .orderByDesc(Execution::getId));
        var rows = page.getRecords().stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", e.getTaskId());
            row.put("tenantId", e.getTenantId());
            row.put("skillCode", e.getSkillCode());
            row.put("version", e.getSkillVersion());
            row.put("status", e.getStatus());
            row.put("progress", e.getProgress());
            row.put("errorCode", e.getErrorCode());
            row.put("holdId", e.getHoldId());
            row.put("pricingSnapshot", e.getPricingSnapshot());
            row.put("durationMs", e.getDurationMs());
            row.put("tokensUsed", e.getTokensUsed());
            row.put("modelCalls", e.getModelCalls());
            row.put("callbackStatus", e.getCallbackStatus());
            row.put("callbackUrl", e.getCallbackUrl());
            row.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            return row;
        }).toList();
        var result = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>>(
                page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(rows);
        return result;
    }

    public Map<String, Object> resendCallback(String taskId) {
        Execution execution = requireExecution(taskId);
        CallerContext synthetic = new CallerContext(execution.getAppKeyId(), execution.getTenantId());
        var body = executionQueryService.get(synthetic, taskId);
        int attempt = 1;
        String bodyJson = com.skill.platform.gateway.service.JsonCodec.write(body);
        callbackRetryProducer.send(taskId, bodyJson, attempt);
        return Map.of("taskId", taskId, "attempt", attempt, "queued", true);
    }

    public Map<String, Object> giveUpCallback(String taskId) {
        Execution execution = requireExecution(taskId);
        int updated = executionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Execution>()
                        .eq(Execution::getTaskId, taskId)
                        .set(Execution::getCallbackStatus, "GIVE_UP"));
        return Map.of("taskId", taskId, "updated", updated);
    }

    private Execution requireExecution(String taskId) {
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, taskId).last("LIMIT 1"));
        if (execution == null) {
            throw new com.skill.platform.gateway.common.BizException(
                    com.skill.platform.gateway.common.ErrorCode.SKILL_NOT_FOUND, "任务不存在: " + taskId);
        }
        return execution;
    }
}
