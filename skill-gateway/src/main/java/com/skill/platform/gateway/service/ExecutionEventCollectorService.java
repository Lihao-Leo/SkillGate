package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行事件 collector（技术方案 v2.0 §1.4）：execution_event 单点写库通道。
 * seq 按 taskId 分组、自当前 max(seq) 连续分配；观测数据定位——
 * 参数类失败整批拒绝（controller 转 400），DB 类失败仅告警丢弃、绝不阻塞执行主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEventCollectorService {

    /** 单批上限（internal API 整批校验，超限 40001） */
    public static final int MAX_BATCH = 100;

    private final ExecutionEventMapper executionEventMapper;
    private final ObjectMapper objectMapper;

    /**
     * 上报事件输入；detail 为任意 JSON 可序列化对象（落库前字符串化，String 原样透传）。
     */
    public record EventInput(String taskId, String tenantId, String stage, String status,
                             String service, Long durationMs, Object detail, String traceId,
                             LocalDateTime occurredAt) {
    }

    /**
     * 批量落库执行事件。
     *
     * @param events 事件列表（同任务内顺序即展示顺序）
     * @return 实际落库条数（accepted）；DB 故障时返回 0
     * @throws BizException 空批/超 {@link #MAX_BATCH}/缺 taskId 或 stage（整批拒绝语义）
     */
    public int collectBatch(List<EventInput> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        if (events.size() > MAX_BATCH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单批事件数超上限 " + MAX_BATCH);
        }
        for (EventInput e : events) {
            if (e.taskId() == null || e.taskId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "taskId 不能为空");
            }
            if (e.stage() == null || e.stage().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "stage 不能为空");
            }
        }
        try {
            Map<String, List<EventInput>> byTask = new LinkedHashMap<>();
            for (EventInput e : events) {
                byTask.computeIfAbsent(e.taskId(), k -> new ArrayList<>()).add(e);
            }
            int inserted = 0;
            for (Map.Entry<String, List<EventInput>> entry : byTask.entrySet()) {
                int seq = currentMaxSeq(entry.getKey());
                for (EventInput e : entry.getValue()) {
                    executionEventMapper.insert(toEntity(e, ++seq));
                    inserted++;
                }
            }
            return inserted;
        } catch (Exception dbError) {
            log.warn("execution_event 落库失败，本批 {} 条事件丢弃（观测不阻塞主链路）: {}",
                    events.size(), dbError.getMessage());
            return 0;
        }
    }

    /** 当前任务已落库的最大 seq（无记录视为 0） */
    private int currentMaxSeq(String taskId) {
        ExecutionEvent highest = executionEventMapper.selectOne(
                new LambdaQueryWrapper<ExecutionEvent>()
                        .eq(ExecutionEvent::getTaskId, taskId)
                        .orderByDesc(ExecutionEvent::getSeq)
                        .last("LIMIT 1"));
        return highest == null || highest.getSeq() == null ? 0 : highest.getSeq();
    }

    private ExecutionEvent toEntity(EventInput e, int seq) {
        ExecutionEvent entity = new ExecutionEvent();
        entity.setTaskId(e.taskId());
        entity.setTenantId(e.tenantId());
        entity.setSeq(seq);
        entity.setStage(e.stage());
        entity.setStatus(e.status() == null || e.status().isBlank() ? "OK" : e.status());
        entity.setService(e.service() == null || e.service().isBlank() ? "gateway" : e.service());
        entity.setDurationMs(e.durationMs());
        entity.setDetail(stringify(e.detail()));
        entity.setTraceId(e.traceId());
        entity.setOccurredAt(e.occurredAt() == null ? LocalDateTime.now() : e.occurredAt());
        return entity;
    }

    /** detail 字符串化：JSON 序列化失败降级为 toString，不因观测数据格式问题阻塞落库 */
    private String stringify(Object detail) {
        if (detail == null) {
            return null;
        }
        if (detail instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException jsonError) {
            return String.valueOf(detail);
        }
    }
}
