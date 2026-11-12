package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AlertRecord;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.entity.Issue;
import com.skill.platform.gateway.dal.mapper.AlertRecordMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.dal.mapper.IssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问题工单（V2.3）：告警 / 失败任务 / 手工三类来源的归因→修复→验证→关单闭环。
 *
 * <p>状态机：OPEN→FIXING→RESOLVED→CLOSED（终态）+ RESOLVED→FIXING 回退（resolution 留痕）。
 * 同来源幂等转单：uk(type, source_id) 冲突时返回已存在工单（告警复发不新开）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueMapper issueMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final ExecutionMapper executionMapper;

    /** 工单来源创建入参（来源预填由 Controller/前端组装）。 */
    public record IssueCreateInput(String title, String type, String severity, String sourceId,
                                   String relatedTaskId, String tenantId, String skillCode,
                                   String description, String assignee, String operator) {
    }

    /** 编辑入参（不动状态）。 */
    public record IssuePatch(String title, String severity, String description,
                             String rootCause, String resolution, String assignee) {
    }

    private static final Set<String> VALID_TYPES =
            Set.of(Issue.TYPE_ALERT, Issue.TYPE_EXECUTION_FAILED, Issue.TYPE_MANUAL);
    private static final Set<String> VALID_STATUSES =
            Set.of(Issue.STATUS_OPEN, Issue.STATUS_FIXING, Issue.STATUS_RESOLVED, Issue.STATUS_CLOSED);

    /** 创建（同来源幂等：uk 冲突时返回已存在工单，alreadyExists=true）。 */
    public Map<String, Object> create(IssueCreateInput input) {
        if (input.title() == null || input.title().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "title 不能为空");
        }
        if (input.type() == null || !VALID_TYPES.contains(input.type())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "type 仅支持 " + String.join("/", VALID_TYPES));
        }
        Issue issue = new Issue();
        issue.setTicketNo(com.skill.platform.gateway.common.Ids.next("tk"));
        issue.setTitle(input.title().trim());
        issue.setType(input.type());
        issue.setSeverity(input.severity() == null || input.severity().isBlank()
                ? "WARN" : input.severity());
        issue.setStatus(Issue.STATUS_OPEN);
        issue.setSourceId(input.sourceId());
        issue.setRelatedTaskId(input.relatedTaskId());
        issue.setTenantId(input.tenantId());
        issue.setSkillCode(input.skillCode());
        issue.setDescription(input.description());
        issue.setAssignee(input.assignee());
        issue.setCreatedBy(input.operator());
        try {
            issueMapper.insert(issue);
        } catch (DuplicateKeyException duplicated) {
            // 同来源幂等：告警/失败任务重复转单 → 返回已存在工单
            Issue existing = issueMapper.selectOne(new LambdaQueryWrapper<Issue>()
                    .eq(Issue::getType, input.type())
                    .eq(input.sourceId() != null, Issue::getSourceId, input.sourceId())
                    .last("LIMIT 1"));
            if (existing != null) {
                return Map.of("issue", existing, "alreadyExists", true);
            }
            throw duplicated;
        }
        log.info("issue created: ticket={}, type={}, source={}, by={}",
                issue.getTicketNo(), issue.getType(), issue.getSourceId(), input.operator());
        return Map.of("issue", issue, "alreadyExists", false);
    }

    /** 分页筛选（status/type/severity/assignee + keyword 命中 ticket_no/title/related_task_id）。 */
    public Page<Issue> page(String status, String type, String severity, String assignee,
                            String keyword, int pageNo, int pageSize) {
        LambdaQueryWrapper<Issue> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) wrapper.eq(Issue::getStatus, status);
        if (type != null && !type.isBlank()) wrapper.eq(Issue::getType, type);
        if (severity != null && !severity.isBlank()) wrapper.eq(Issue::getSeverity, severity);
        if (assignee != null && !assignee.isBlank()) wrapper.eq(Issue::getAssignee, assignee);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Issue::getTicketNo, kw)
                    .or().like(Issue::getTitle, kw)
                    .or().like(Issue::getRelatedTaskId, kw));
        }
        wrapper.orderByDesc(Issue::getUpdatedAt);
        return issueMapper.selectPage(new Page<>(Math.max(pageNo, 1),
                Math.min(Math.max(pageSize, 1), 100)), wrapper);
    }

    public Issue get(Long id) {
        Issue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "工单不存在");
        }
        return issue;
    }

    /** 详情 + 来源对象（ALERT→告警原文；EXECUTION_FAILED→执行摘要）。 */
    public Map<String, Object> getWithSource(Long id) {
        Issue issue = get(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("issue", issue);
        if (Issue.TYPE_ALERT.equals(issue.getType()) && issue.getSourceId() != null) {
            try {
                view.put("sourceAlert", alertRecordMapper.selectById(Long.valueOf(issue.getSourceId())));
            } catch (NumberFormatException ignore) {
                // source_id 非数字（历史数据）忽略
            }
        } else if (Issue.TYPE_EXECUTION_FAILED.equals(issue.getType())
                && issue.getRelatedTaskId() != null) {
            Execution execution = executionMapper.selectOne(
                    new LambdaQueryWrapper<Execution>()
                            .eq(Execution::getTaskId, issue.getRelatedTaskId()).last("LIMIT 1"));
            if (execution != null) {
                view.put("sourceExecution", Map.of(
                        "taskId", execution.getTaskId(),
                        "status", execution.getStatus(),
                        "errorCode", execution.getErrorCode() == null ? "" : execution.getErrorCode(),
                        "errorMessage", execution.getErrorMessage() == null
                                ? "" : execution.getErrorMessage(),
                        "durationMs", execution.getDurationMs() == null ? 0 : execution.getDurationMs()));
            }
        }
        return view;
    }

    /** 编辑字段（不动状态）。 */
    public Issue update(Long id, IssuePatch patch) {
        Issue issue = get(id);
        if (patch.title() != null && !patch.title().isBlank()) {
            issue.setTitle(patch.title().trim());
        }
        if (patch.severity() != null && !patch.severity().isBlank()) {
            issue.setSeverity(patch.severity());
        }
        if (patch.description() != null) {
            issue.setDescription(patch.description());
        }
        if (patch.rootCause() != null) {
            issue.setRootCause(patch.rootCause());
        }
        if (patch.resolution() != null) {
            issue.setResolution(patch.resolution());
        }
        if (patch.assignee() != null) {
            issue.setAssignee(patch.assignee().isBlank() ? null : patch.assignee());
        }
        issueMapper.updateById(issue);
        return issue;
    }

    /** 状态流转（服务端强制状态机校验；FIXING→RESOLVED 必填 resolution；CLOSED 写 closed_at）。 */
    public Issue transition(Long id, String target, String operator,
                            String assignee, String resolution) {
        Issue issue = get(id);
        if (target == null || !VALID_STATUSES.contains(target)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标状态非法: " + target);
        }
        String current = issue.getStatus();
        boolean allowed = switch (current) {
            case Issue.STATUS_OPEN -> Issue.STATUS_FIXING.equals(target)
                    || Issue.STATUS_RESOLVED.equals(target) || Issue.STATUS_CLOSED.equals(target);
            case Issue.STATUS_FIXING -> Issue.STATUS_RESOLVED.equals(target)
                    || Issue.STATUS_CLOSED.equals(target);
            case Issue.STATUS_RESOLVED -> Issue.STATUS_CLOSED.equals(target)
                    || Issue.STATUS_FIXING.equals(target);
            default -> false; // CLOSED 终态不可逆（审计底线）
        };
        if (!allowed) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "状态不允许流转: " + current + " → " + target);
        }
        if (Issue.STATUS_RESOLVED.equals(target)
                && (resolution == null || resolution.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "转 RESOLVED 必须填写解决方案（resolution）");
        }
        issue.setStatus(target);
        if (assignee != null && !assignee.isBlank()) {
            issue.setAssignee(assignee);
        }
        if (Issue.STATUS_CLOSED.equals(target)) {
            issue.setClosedAt(LocalDateTime.now());
        }
        if (resolution != null && !resolution.isBlank()) {
            issue.setResolution(resolution);
        }
        issueMapper.updateById(issue);
        log.info("issue transitioned: ticket={}, {} -> {}, by={}",
                issue.getTicketNo(), current, target, operator);
        return issue;
    }

    /** 同来源关联工单（执行抽屉 / 告警行展示）。 */
    public List<Issue> listBySource(String type, String sourceId) {
        return issueMapper.selectList(new LambdaQueryWrapper<Issue>()
                .eq(Issue::getType, type)
                .eq(Issue::getSourceId, sourceId)
                .orderByDesc(Issue::getUpdatedAt));
    }
}
