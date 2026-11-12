package com.skill.platform.gateway.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.entity.SkillIoLog;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 日志查询（V2.3）双源：分发埋点（skill_io_log）+ 任务事件链（execution_event）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@RequirePermission(Permissions.EXECUTION_READ)
public class AdminLogController {

    private final LogQueryService logQueryService;

    /** 分发埋点日志（FREE Skill 调用留痕；走 idx_skill_created）。 */
    @GetMapping("/io-logs")
    public ApiResponse<Page<SkillIoLog>> ioLogs(
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String status,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(logQueryService.ioLogPage(skillCode, status, from, to,
                pageNo, pageSize));
    }

    /** 任务事件检索（taskId 主检索；无 taskId 强制 ≤7 天窗 + LIMIT 200）。 */
    @GetMapping("/execution-events/search")
    public ApiResponse<Page<ExecutionEvent>> searchEvents(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String status,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(logQueryService.eventPage(taskId, stage, status, from, to,
                pageNo, pageSize));
    }
}
