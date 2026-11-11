package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AdminContext;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.dal.entity.AlertRecord;
import com.skill.platform.gateway.service.AlertRuleService;
import com.skill.platform.gateway.service.AlertRuleService.RuleView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警中心（V2.1）：规则 CRUD（alert:manage）+ 告警记录查询/认领（alert:read / alert:manage）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAlertController {

    private final AlertRuleService alertRuleService;

    // ---- 规则 ----

    @GetMapping("/alert-rules")
    @RequirePermission(Permissions.ALERT_READ)
    public ApiResponse<List<RuleView>> listRules() {
        return ApiResponse.ok(alertRuleService.list());
    }

    @PostMapping("/alert-rules")
    @RequirePermission(Permissions.ALERT_MANAGE)
    public ApiResponse<RuleView> createRule(@RequestBody AlertRulePayload payload) {
        return ApiResponse.ok(alertRuleService.create(payload.toEntity()));
    }

    @PutMapping("/alert-rules/{id}")
    @RequirePermission(Permissions.ALERT_MANAGE)
    public ApiResponse<RuleView> updateRule(@PathVariable Long id,
                                            @RequestBody AlertRulePayload payload) {
        return ApiResponse.ok(alertRuleService.update(id, payload.toEntity()));
    }

    @DeleteMapping("/alert-rules/{id}")
    @RequirePermission(Permissions.ALERT_MANAGE)
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        alertRuleService.delete(id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/alert-rules/{id}/status")
    @RequirePermission(Permissions.ALERT_MANAGE)
    public ApiResponse<RuleView> updateRuleStatus(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> request) {
        Object enabled = request.get("enabled");
        return ApiResponse.ok(alertRuleService.updateStatus(id,
                enabled instanceof Number number ? number.intValue() : null));
    }

    // ---- 告警记录 ----

    @GetMapping("/alert-records")
    @RequirePermission(Permissions.ALERT_READ)
    public ApiResponse<Map<String, Object>> listRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(alertRuleService.listRecords(status, ruleType, taskId, skillCode,
                pageNo, pageSize));
    }

    @GetMapping("/alert-records/pending-count")
    @RequirePermission(Permissions.ALERT_READ)
    public ApiResponse<Map<String, Object>> pendingCount() {
        return ApiResponse.ok(Map.of("count", alertRuleService.pendingCount()));
    }

    @PutMapping("/alert-records/{id}/ack")
    @RequirePermission(Permissions.ALERT_MANAGE)
    public ApiResponse<AlertRecord> ack(@RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
                                        @PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> request) {
        return ApiResponse.ok(alertRuleService.ack(id, admin.username(),
                request == null ? null : request.get("note")));
    }

    /** 规则创建/更新入参。 */
    public record AlertRulePayload(String ruleCode, String ruleName, String ruleType,
                                   String skillCode, Long thresholdValue, String severity,
                                   String channels, Integer dedupWindowSeconds, Integer enabled) {
        com.skill.platform.gateway.dal.entity.AlertRule toEntity() {
            com.skill.platform.gateway.dal.entity.AlertRule rule =
                    new com.skill.platform.gateway.dal.entity.AlertRule();
            rule.setRuleCode(ruleCode);
            rule.setRuleName(ruleName);
            rule.setRuleType(ruleType);
            rule.setSkillCode(skillCode);
            rule.setThresholdValue(thresholdValue);
            rule.setSeverity(severity);
            rule.setChannels(channels);
            rule.setDedupWindowSeconds(dedupWindowSeconds);
            rule.setEnabled(enabled);
            return rule;
        }
    }
}
