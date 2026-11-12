package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.dal.entity.Budget;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.service.BudgetService;
import com.skill.platform.gateway.service.ConsumptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消耗看板（V2.2）：Token 与第三方接口消耗聚合、失败成本、预算管理、成本 vs 收入对账。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ConsumptionController {

    private final ConsumptionService consumptionService;
    private final BudgetService budgetService;

    /** 筛选参数 → Criteria（hours 范围默认 30 天，上限 720）。 */
    private static ConsumptionService.Criteria criteria(
            int hours, String tenantId, String skillCode, String alias) {
        int h = Math.min(Math.max(hours, 1), 720);
        return new ConsumptionService.Criteria(
                LocalDateTime.now().minusHours(h), tenantId, skillCode, alias);
    }

    @GetMapping("/consumption/summary")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<Map<String, Object>> summary(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias) {
        return ApiResponse.ok(consumptionService.summary(criteria(hours, tenantId, skillCode, alias)));
    }

    @GetMapping("/consumption/by-skill")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<List<Map<String, Object>>> bySkill(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias) {
        return ApiResponse.ok(consumptionService.bySkill(criteria(hours, tenantId, skillCode, alias)));
    }

    @GetMapping("/consumption/by-model")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<List<Map<String, Object>>> byModel(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias) {
        return ApiResponse.ok(consumptionService.byModel(criteria(hours, tenantId, skillCode, alias)));
    }

    @GetMapping("/consumption/by-tenant")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<List<Map<String, Object>>> byTenant(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias) {
        return ApiResponse.ok(consumptionService.byTenant(criteria(hours, tenantId, skillCode, alias)));
    }

    @GetMapping("/consumption/daily")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<List<Map<String, Object>>> daily(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias) {
        return ApiResponse.ok(consumptionService.daily(criteria(hours, tenantId, skillCode, alias)));
    }

    /** 任务对照表：model_call 折算成本 vs 定价口径应收点数（成本 vs 收入）。 */
    @GetMapping("/consumption/tasks")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<Map<String, Object>> tasks(
            @RequestParam(name = "hours", defaultValue = "720") int hours,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String alias,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(consumptionService.taskLedger(
                criteria(hours, tenantId, skillCode, alias), pageNo, pageSize));
    }

    /** 预算列表（含本月已用与百分比）。 */
    @GetMapping("/budgets")
    @RequirePermission(Permissions.CONSUMPTION_READ)
    public ApiResponse<List<Map<String, Object>>> budgets() {
        return ApiResponse.ok(consumptionService.budgetStatus());
    }

    /** 新建预算。 */
    @PostMapping("/budgets")
    @RequirePermission(Permissions.CONSUMPTION_MANAGE)
    public ApiResponse<Budget> createBudget(@RequestBody BudgetPayload payload) {
        return ApiResponse.ok(budgetService.create(payload.toEntity()));
    }

    /** 更新预算（额度/阈值/启停）。 */
    @PutMapping("/budgets/{id}")
    @RequirePermission(Permissions.CONSUMPTION_MANAGE)
    public ApiResponse<Budget> updateBudget(@PathVariable Long id,
                                            @RequestBody BudgetPayload payload) {
        return ApiResponse.ok(budgetService.update(id, payload.toEntity()));
    }

    /** 删除预算。 */
    @DeleteMapping("/budgets/{id}")
    @RequirePermission(Permissions.CONSUMPTION_MANAGE)
    public ApiResponse<Void> deleteBudget(@PathVariable Long id) {
        budgetService.delete(id);
        return ApiResponse.ok();
    }

    /** 预算启停。 */
    @PutMapping("/budgets/{id}/status")
    @RequirePermission(Permissions.CONSUMPTION_MANAGE)
    public ApiResponse<Budget> updateBudgetStatus(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> request) {
        Object enabled = request.get("enabled");
        return ApiResponse.ok(budgetService.updateStatus(id,
                enabled instanceof Number number ? number.intValue() : null));
    }

    /** 预算创建/更新入参。 */
    public record BudgetPayload(String scopeType, String scopeValue, Long amountPoints,
                                Integer alertThresholdPct, Integer enabled) {
        Budget toEntity() {
            Budget budget = new Budget();
            budget.setScopeType(scopeType);
            budget.setScopeValue(scopeValue == null ? "" : scopeValue);
            budget.setPeriodType("MONTHLY");
            budget.setAmountPoints(amountPoints);
            budget.setAlertThresholdPct(alertThresholdPct);
            budget.setEnabled(enabled);
            return budget;
        }
    }
}
