package com.skill.platform.gateway.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.dal.entity.Issue;
import com.skill.platform.gateway.security.AdminContext;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.service.IssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 问题工单（V2.3）：告警 / 失败任务 / 手工三类来源的归因→修复→验证→关单闭环。
 */
@RestController
@RequestMapping("/api/v1/admin/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    /** 分页筛选（status/type/severity/assignee + keyword 命中 ticket_no/title/taskId）。 */
    @GetMapping
    @RequirePermission(Permissions.ISSUE_READ)
    public ApiResponse<Page<Issue>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(issueService.page(status, type, severity, assignee, keyword,
                pageNo, pageSize));
    }

    /** 详情（含来源对象：告警原文 / 执行摘要）。 */
    @GetMapping("/{id}")
    @RequirePermission(Permissions.ISSUE_READ)
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(issueService.getWithSource(id));
    }

    /** 同来源关联工单（执行抽屉 / 告警行展示）。 */
    @GetMapping("/by-source")
    @RequirePermission(Permissions.ISSUE_READ)
    public ApiResponse<java.util.List<Issue>> listBySource(
            @RequestParam String type, @RequestParam String sourceId) {
        return ApiResponse.ok(issueService.listBySource(type, sourceId));
    }

    /** 创建工单（来源预填或 MANUAL 手工；同来源幂等转单）。 */
    @PostMapping
    @RequirePermission(Permissions.ISSUE_MANAGE)
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
            @RequestBody CreatePayload payload) {
        return ApiResponse.ok(issueService.create(new IssueService.IssueCreateInput(
                payload.title(), payload.type(), payload.severity(), payload.sourceId(),
                payload.relatedTaskId(), payload.tenantId(), payload.skillCode(),
                payload.description(), payload.assignee(), admin.username())));
    }

    /** 编辑字段（标题/级别/描述/根因/方案/负责人）。 */
    @PutMapping("/{id}")
    @RequirePermission(Permissions.ISSUE_MANAGE)
    public ApiResponse<Issue> update(@PathVariable Long id, @RequestBody IssuePatchPayload payload) {
        return ApiResponse.ok(issueService.update(id, new IssueService.IssuePatch(
                payload.title(), payload.severity(), payload.description(),
                payload.rootCause(), payload.resolution(), payload.assignee())));
    }

    /** 状态流转（OPEN→FIXING→RESOLVED→CLOSED；RESOLVED 可回退 FIXING）。 */
    @PostMapping("/{id}/transition")
    @RequirePermission(Permissions.ISSUE_MANAGE)
    public ApiResponse<Issue> transition(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
            @PathVariable Long id, @RequestBody TransitionPayload payload) {
        return ApiResponse.ok(issueService.transition(id, payload.targetStatus(),
                admin.username(), payload.assignee(), payload.resolution()));
    }

    /** 创建入参。 */
    public record CreatePayload(String title, String type, String severity, String sourceId,
                                String relatedTaskId, String tenantId, String skillCode,
                                String description, String assignee) {
    }

    /** 编辑入参。 */
    public record IssuePatchPayload(String title, String severity, String description,
                                    String rootCause, String resolution, String assignee) {
    }

    /** 流转入参。 */
    public record TransitionPayload(String targetStatus, String assignee, String resolution) {
    }
}
