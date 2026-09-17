package com.skill.platform.gateway.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.ExecuteDtos.ExecuteResult;
import com.skill.platform.gateway.service.ExecuteDtos.CancelResult;
import com.skill.platform.gateway.service.ExecuteDtos.ExecutionView;
import com.skill.platform.gateway.service.ExecuteService;
import com.skill.platform.gateway.service.ExecutionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询与取消（§5.4）：终态查询响应与回调 body 同构；cancel 状态机见 ExecutionQueryService。
 */
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionQueryService executionQueryService;
    private final ExecuteService executeService;

    /** 最近任务列表（§10.1 执行监控）：本租户分页，轻量行 */
    @GetMapping
    public ApiResponse<Page<ExecutionView>> list(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(executionQueryService.list(caller, status, pageNo, pageSize));
    }

    /** 失败任务重试（§10.1）：按原入参重放一次完整受理（新 taskId） */
    @PostMapping("/{taskId}/retry")
    public ApiResponse<ExecuteResult> retry(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String taskId) {
        return ApiResponse.ok(executeService.retry(caller, taskId));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ExecutionView> get(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String taskId) {
        return ApiResponse.ok(executionQueryService.get(caller, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<CancelResult> cancel(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String taskId) {
        return ApiResponse.ok(executionQueryService.cancel(caller, taskId));
    }
}
