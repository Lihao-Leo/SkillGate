package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.entity.SkillIoLog;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
import com.skill.platform.gateway.dal.mapper.SkillIoLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 日志查询（V2.3）双源：FREE 分发埋点（skill_io_log）+ 平台任务事件链（execution_event）。
 * 事件检索护栏：无 taskId 时强制 ≤7 天时间窗 + LIMIT 200（execution_event 无 stage 维度索引）。
 */
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private static final int MAX_LIMIT = 200;
    private static final int MAX_SCAN_DAYS = 7;

    private final SkillIoLogMapper skillIoLogMapper;
    private final ExecutionEventMapper executionEventMapper;

    /** 分发埋点日志分页（skill_code/状态/时间窗，走 idx_skill_created）。 */
    public Page<SkillIoLog> ioLogPage(String skillCode, String status,
                                      LocalDateTime from, LocalDateTime to,
                                      int pageNo, int pageSize) {
        QueryWrapper<SkillIoLog> wrapper = new QueryWrapper<>();
        if (skillCode != null && !skillCode.isBlank()) wrapper.eq("skill_code", skillCode);
        if (status != null && !status.isBlank()) wrapper.eq("status", status);
        if (from != null) wrapper.ge("created_at", from);
        if (to != null) wrapper.le("created_at", to);
        wrapper.orderByDesc("created_at");
        return skillIoLogMapper.selectPage(
                new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100)), wrapper);
    }

    /**
     * 任务事件检索分页。taskId 走 idx_task_seq 精确；无 taskId 强制时间窗 ≤ 7 天，
     * pageSize 钳制 ≤ 200（防无 taskId 时全表扫描）。
     */
    public Page<ExecutionEvent> eventPage(String taskId, String stage, String status,
                                          LocalDateTime from, LocalDateTime to,
                                          int pageNo, int pageSize) {
        QueryWrapper<ExecutionEvent> wrapper = new QueryWrapper<>();
        boolean byTask = taskId != null && !taskId.isBlank();
        if (byTask) {
            wrapper.eq("task_id", taskId);
        } else {
            // 护栏：无 taskId 检索强制时间窗，防全表扫描
            LocalDateTime safeFrom = from != null ? from : LocalDateTime.now().minusDays(MAX_SCAN_DAYS);
            wrapper.ge("occurred_at", safeFrom);
        }
        if (stage != null && !stage.isBlank()) wrapper.eq("stage", stage);
        if (status != null && !status.isBlank()) wrapper.eq("status", status);
        if (from != null) wrapper.ge("occurred_at", from);
        if (to != null) wrapper.le("occurred_at", to);
        wrapper.orderByDesc("occurred_at");
        return executionEventMapper.selectPage(
                new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), MAX_LIMIT)), wrapper);
    }
}
