package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AlertRecord;
import com.skill.platform.gateway.dal.entity.AlertRule;
import com.skill.platform.gateway.dal.mapper.AlertRecordMapper;
import com.skill.platform.gateway.dal.mapper.AlertRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警规则中心（V2.1）：规则 CRUD + 规则下发视图（worker 60s 拉取）+ 告警记录查询/认领。
 * 触发判定在 worker（watchdog/orchestrator），gateway 只管规则与落库。
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRecordMapper alertRecordMapper;

    /** 规则视图（下发/列表共用）。 */
    public record RuleView(Long id, String ruleCode, String ruleName, String ruleType,
                           String skillCode, Long thresholdValue, String severity,
                           String channels, Integer dedupWindowSeconds, Integer enabled) {
    }

    /** worker 下发视图：仅启用规则的最小集。 */
    public List<Map<String, Object>> enabledRules() {
        return alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getEnabled, 1))
                .stream().map(this::toDispatchView).toList();
    }

    /** 规则列表。 */
    public List<RuleView> list() {
        return alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .orderByDesc(AlertRule::getEnabled)
                .orderByAsc(AlertRule::getId))
                .stream().map(AlertRuleService::toView).toList();
    }

    /** 新建规则。 */
    public RuleView create(AlertRule rule) {
        validate(rule);
        if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
            rule.setSeverity("WARN");
        }
        if (rule.getChannels() == null || rule.getChannels().isBlank()) {
            rule.setChannels("feishu,record");
        }
        if (rule.getDedupWindowSeconds() == null || rule.getDedupWindowSeconds() <= 0) {
            rule.setDedupWindowSeconds(86400);
        }
        rule.setEnabled(rule.getEnabled() == null ? 1 : rule.getEnabled());
        alertRuleMapper.insert(rule);
        return toView(rule);
    }

    /** 更新规则（ruleCode 不可改）。 */
    public RuleView update(Long id, AlertRule patch) {
        AlertRule existing = mustGet(id);
        validate(patch);
        if (patch.getRuleName() != null) {
            existing.setRuleName(patch.getRuleName());
        }
        existing.setRuleType(patch.getRuleType() != null ? patch.getRuleType() : existing.getRuleType());
        existing.setSkillCode(patch.getSkillCode());
        existing.setThresholdValue(patch.getThresholdValue());
        if (patch.getSeverity() != null) {
            existing.setSeverity(patch.getSeverity());
        }
        if (patch.getChannels() != null) {
            existing.setChannels(patch.getChannels());
        }
        if (patch.getDedupWindowSeconds() != null) {
            existing.setDedupWindowSeconds(patch.getDedupWindowSeconds());
        }
        if (patch.getEnabled() != null) {
            existing.setEnabled(patch.getEnabled());
        }
        alertRuleMapper.updateById(existing);
        return toView(existing);
    }

    /** 删除规则。 */
    public void delete(Long id) {
        mustGet(id);
        alertRuleMapper.deleteById(id);
    }

    /** 启停。 */
    public RuleView updateStatus(Long id, Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enabled 仅允许 0/1");
        }
        AlertRule existing = mustGet(id);
        existing.setEnabled(enabled);
        alertRuleMapper.updateById(existing);
        return toView(existing);
    }

    // ------------------------------------------------------------------
    // 告警记录
    // ------------------------------------------------------------------

    /** 告警记录分页（status/ruleType/taskId/skillCode 筛选）。 */
    public Map<String, Object> listRecords(String status, String ruleType, String taskId,
                                           String skillCode, int pageNo, int pageSize) {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(AlertRecord::getStatus, status);
        }
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(AlertRecord::getRuleType, ruleType);
        }
        if (taskId != null && !taskId.isBlank()) {
            wrapper.eq(AlertRecord::getTaskId, taskId);
        }
        if (skillCode != null && !skillCode.isBlank()) {
            wrapper.eq(AlertRecord::getSkillCode, skillCode);
        }
        wrapper.orderByDesc(AlertRecord::getLastSeenAt);
        Page<AlertRecord> page = alertRecordMapper.selectPage(
                new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 200)), wrapper);
        return Map.of("total", page.getTotal(), "pageNo", page.getCurrent(),
                "pageSize", page.getSize(), "rows", page.getRecords());
    }

    /** 待处理数（大屏徽标 / KPI）。 */
    public long pendingCount() {
        return alertRecordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getStatus, AlertRecord.STATUS_PENDING));
    }

    /** 认领：PENDING → ACKED（ack_by=当前后台用户）。 */
    public AlertRecord ack(Long id, String operator, String note) {
        AlertRecord record = alertRecordMapper.selectById(id);
        if (record == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "告警记录不存在");
        }
        if (!AlertRecord.STATUS_PENDING.equals(record.getStatus())) {
            return record;
        }
        AlertRecord update = new AlertRecord();
        update.setId(record.getId());
        update.setStatus(AlertRecord.STATUS_ACKED);
        update.setAckBy(operator);
        update.setAckAt(LocalDateTime.now());
        update.setAckNote(note);
        alertRecordMapper.updateById(update);
        return alertRecordMapper.selectById(id);
    }

    // ------------------------------------------------------------------

    private AlertRule mustGet(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "告警规则不存在");
        }
        return rule;
    }

    private void validate(AlertRule rule) {
        if (rule.getRuleCode() == null || rule.getRuleCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "ruleCode 不能为空");
        }
        List<String> validTypes = List.of(AlertRule.TYPE_RUNNING_TIMEOUT, AlertRule.TYPE_TOKEN_EXCESS,
                AlertRule.TYPE_TASK_FAILED, AlertRule.TYPE_PENDING_STUCK,
                AlertRule.TYPE_CALLBACK_DEAD_LETTER, AlertRule.TYPE_WATCHDOG_INTERVENTION);
        if (rule.getRuleType() == null || !validTypes.contains(rule.getRuleType())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "ruleType 仅支持 " + String.join("/", validTypes));
        }
        if (rule.getThresholdValue() == null || rule.getThresholdValue() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "thresholdValue 必须为正数");
        }
    }

    private Map<String, Object> toDispatchView(AlertRule rule) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("ruleId", rule.getId());
        view.put("ruleCode", rule.getRuleCode());
        view.put("ruleType", rule.getRuleType());
        view.put("skillCode", rule.getSkillCode());
        view.put("thresholdValue", rule.getThresholdValue());
        view.put("severity", rule.getSeverity());
        view.put("channels", rule.getChannels());
        view.put("dedupWindowSeconds", rule.getDedupWindowSeconds());
        return view;
    }

    private static RuleView toView(AlertRule rule) {
        return new RuleView(rule.getId(), rule.getRuleCode(), rule.getRuleName(), rule.getRuleType(),
                rule.getSkillCode(), rule.getThresholdValue(), rule.getSeverity(),
                rule.getChannels(), rule.getDedupWindowSeconds(), rule.getEnabled());
    }
}
