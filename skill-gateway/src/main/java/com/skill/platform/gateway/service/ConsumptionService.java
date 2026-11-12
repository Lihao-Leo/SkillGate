package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skill.platform.gateway.dal.entity.AlertRecord;
import com.skill.platform.gateway.dal.entity.Budget;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.entity.ModelCall;
import com.skill.platform.gateway.dal.mapper.AlertRecordMapper;
import com.skill.platform.gateway.dal.mapper.BudgetMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.dal.mapper.ModelCallMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 消耗看板（V2.2）：基于 model_call 上报明细的聚合视图 + 月度预算管理。
 *
 * <p>口径说明：cost_points 为按 model_provider 费率折算的「模型真实消耗成本」，
 * 与向租户收取的定价点数（收入）口径不同——两者在任务对照表中并列展示。
 * 预算超限仅产生告警记录（不拦截执行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumptionService {

    private final ModelCallMapper modelCallMapper;
    private final BudgetMapper budgetMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final ExecutionMapper executionMapper;

    /** 聚合筛选条件（时间窗 + 可选维度）。 */
    public record Criteria(LocalDateTime since, String tenantId, String skillCode, String alias) {
    }

    /** 窗口汇总：tokens / 成本 / 调用数 / 失败数与失败成本。 */
    public Map<String, Object> summary(Criteria criteria) {
        Map<String, Object> result = modelCallMapper.selectMaps(summaryWrapper(criteria))
                .stream().findFirst().orElse(Map.of());
        try {
            checkBudgets();
        } catch (Exception e) {
            log.warn("预算告警联动失败（不影响看板）: {}", e.getMessage());
        }
        return result;
    }

    /** 按 Skill 聚合排行。 */
    public List<Map<String, Object>> bySkill(Criteria criteria) {
        return groupBy(criteria, "skill_code", "skill_code", "costPoints");
    }

    /** 按模型聚合排行（alias + provider）。 */
    public List<Map<String, Object>> byModel(Criteria criteria) {
        return groupBy(criteria, "alias, provider", "alias, provider", "costPoints");
    }

    /** 按租户聚合排行。 */
    public List<Map<String, Object>> byTenant(Criteria criteria) {
        return groupBy(criteria, "tenant_id", "tenant_id", "costPoints");
    }

    /** 日趋势（tokens / cost / calls 按日）。 */
    public List<Map<String, Object>> daily(Criteria criteria) {
        return groupBy(criteria, "DATE(created_at) AS day", "DATE(created_at)", "day");
    }

    /**
     * 任务对照表（成本 vs 收入）：按 task 聚合 model_call 折算成本，
     * LEFT execution 取定价口径（应收点数=单价×期望产出；FREE=0；METERED 以封顶计）。
     */
    public Map<String, Object> taskLedger(Criteria criteria, int pageNo, int pageSize) {
        QueryWrapper<ModelCall> wrapper = baseWrapper(criteria);
        wrapper.select(
                "task_id",
                "MAX(tenant_id) AS tenant_id",
                "MAX(skill_code) AS skill_code",
                "COUNT(*) AS calls",
                "COALESCE(SUM(total_tokens),0) AS tokens",
                "COALESCE(SUM(cost_points),0) AS costPoints",
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failedCalls");
        wrapper.groupBy("task_id");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> page =
                modelCallMapper.selectMapsPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize),
                        wrapper);

        // 批量取 execution 的定价快照（应收口径）
        List<Object> taskIds = page.getRecords().stream()
                .map(row -> row.get("task_id")).toList();
        Map<String, com.skill.platform.gateway.dal.entity.Execution> executions =
                taskIds.isEmpty() ? Map.of()
                        : executionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                                .QueryWrapper<com.skill.platform.gateway.dal.entity.Execution>()
                                .in("task_id", taskIds))
                        .stream().collect(java.util.stream.Collectors.toMap(
                                com.skill.platform.gateway.dal.entity.Execution::getTaskId,
                                e -> e));

        List<Map<String, Object>> rows = page.getRecords().stream().map(row -> {
            String id = String.valueOf(row.get("task_id"));
            var execution = executions.get(id);
            long cost = row.get("costPoints") instanceof Number n ? n.longValue() : 0;
            long revenue = 0;
            String pricingNote = "—";
            if (execution != null) {
                com.fasterxml.jackson.databind.JsonNode pricing =
                        JsonCodec.treeOrEmpty(execution.getPricingSnapshot());
                String mode = pricing.path("mode").asText("PER_EXECUTION");
                int expected = Math.max(execution.getExpectedCount() == null ? 1
                        : execution.getExpectedCount(), 1);
                switch (mode) {
                    case "FREE" -> {
                        revenue = 0;
                        pricingNote = "FREE";
                    }
                    case "METERED" -> {
                        revenue = pricing.path("capPoints").asLong(0);
                        pricingNote = "METERED 封顶 " + revenue;
                    }
                    default -> {
                        revenue = pricing.path("points").asLong(0) * expected;
                        pricingNote = pricing.path("points").asLong(0) + " 点/次 × " + expected;
                    }
                }
            }
            Map<String, Object> enriched = new java.util.LinkedHashMap<>(row);
            enriched.put("revenuePoints", revenue);
            enriched.put("marginPoints", revenue - cost);
            enriched.put("pricingNote", pricingNote);
            enriched.put("executionStatus", execution == null ? null : execution.getStatus());
            return enriched;
        }).toList();

        return Map.of("total", page.getTotal(), "pageNo", page.getCurrent(),
                "pageSize", page.getSize(), "rows", rows);
    }


    /** 预算列表 + 本月已用与百分比。 */
    public List<Map<String, Object>> budgetStatus() {
        LocalDateTime monthStart = monthStart();
        return budgetMapper.selectList(new QueryWrapper<Budget>()
                .orderByAsc("scope_type").orderByAsc("id"))
                .stream().map(budget -> budgetView(budget, monthStart)).toList();
    }

    // ------------------------------------------------------------------
    // 预算告警联动（仅告警不拦截；同预算单条记录，超限升级 severity）
    // ------------------------------------------------------------------

    /** 对启用预算逐个检查：达阈值百分比 → upsert alert_record（task_id='budget' 哨兵）。 */
    public void checkBudgets() {
        LocalDateTime monthStart = monthStart();
        for (Budget budget : budgetMapper.selectList(new QueryWrapper<Budget>()
                .eq("enabled", 1))) {
            double used = costBetween(monthStart, budgetScopeFilter(budget));
            long amount = budget.getAmountPoints();
            if (amount <= 0 || used <= 0) continue;
            double pct = used * 100.0 / amount;
            if (pct < budget.getAlertThresholdPct()) continue;
            upsertBudgetAlert(budget, pct >= 100 ? "CRITICAL" : "WARN", used, pct);
        }
    }

    private void upsertBudgetAlert(Budget budget, String severity, double used, double pct) {
        String ruleCode = "budget." + budget.getScopeType().toLowerCase()
                + ":" + budget.getScopeValue();
        String title = ("GLOBAL".equals(budget.getScopeType()) ? "全局预算" : "预算")
                + "用量达 " + String.format("%.1f", pct) + "%";
        String content = "scope=" + budget.getScopeType() + ":" + budget.getScopeValue()
                + " 本月已消耗 " + String.format("%.4f", used) + " / 额度 " + budget.getAmountPoints()
                + " 点（仅告警，不拦截执行）";
        AlertRecord existing = alertRecordMapper.selectOne(new QueryWrapper<AlertRecord>()
                .eq("rule_code", ruleCode).eq("task_id", "budget").last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            AlertRecord update = new AlertRecord();
            update.setId(existing.getId());
            update.setSeverity(severity);
            update.setLastSeenAt(now);
            // 从 WARN 升级到 CRITICAL 需要重开为待处理；降级不回退
            if ("CRITICAL".equals(severity) && !"CRITICAL".equals(existing.getSeverity())) {
                update.setStatus(AlertRecord.STATUS_PENDING);
            }
            alertRecordMapper.updateById(update);
            return;
        }
        AlertRecord record = new AlertRecord();
        record.setRuleId(budget.getId());
        record.setRuleCode(ruleCode);
        record.setRuleType("BUDGET_EXCEEDED");
        record.setSeverity(severity);
        record.setStatus(AlertRecord.STATUS_PENDING);
        record.setTaskId("budget");
        record.setSkillCode(Budget.SCOPE_SKILL.equals(budget.getScopeType())
                ? budget.getScopeValue() : null);
        record.setTenantId(Budget.SCOPE_TENANT.equals(budget.getScopeType())
                ? budget.getScopeValue() : null);
        record.setTitle(title);
        record.setContent(content);
        record.setNotified(0);
        record.setFirstSeenAt(now);
        record.setLastSeenAt(now);
        alertRecordMapper.insert(record);
    }

    // ------------------------------------------------------------------
    // 聚合 internals
    // ------------------------------------------------------------------

    private QueryWrapper<ModelCall> summaryWrapper(Criteria c) {
        QueryWrapper<ModelCall> wrapper = baseWrapper(c);
        wrapper.select(
                "COALESCE(SUM(total_tokens),0) AS tokens",
                "COALESCE(SUM(cost_points),0) AS costPoints",
                "COUNT(*) AS calls",
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failedCalls",
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN cost_points ELSE 0 END),0) AS failedCost",
                "COALESCE(AVG(duration_ms),0) AS avgDurationMs",
                "COUNT(DISTINCT alias) AS models");
        return wrapper;
    }

    private List<Map<String, Object>> groupBy(Criteria c, String selectExpr, String groupExpr,
                                              String orderBy) {
        QueryWrapper<ModelCall> wrapper = baseWrapper(c);
        wrapper.select(
                selectExpr,
                "COALESCE(SUM(total_tokens),0) AS tokens",
                "COALESCE(SUM(cost_points),0) AS costPoints",
                "COUNT(*) AS calls",
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failedCalls",
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN cost_points ELSE 0 END),0) AS failedCost");
        wrapper.groupBy(groupExpr).orderByDesc("costPoints");
        return modelCallMapper.selectMaps(wrapper);
    }

    private QueryWrapper<ModelCall> baseWrapper(Criteria c) {
        QueryWrapper<ModelCall> wrapper = new QueryWrapper<>();
        wrapper.ge("created_at", c.since());
        if (c.tenantId() != null && !c.tenantId().isBlank()) {
            wrapper.eq("tenant_id", c.tenantId());
        }
        if (c.skillCode() != null && !c.skillCode().isBlank()) {
            wrapper.eq("skill_code", c.skillCode());
        }
        if (c.alias() != null && !c.alias().isBlank()) {
            wrapper.eq("alias", c.alias());
        }
        return wrapper;
    }

    private Map<String, Object> budgetView(Budget budget, LocalDateTime monthStart) {
        double used = costBetween(monthStart, budgetScopeFilter(budget));
        double pct = budget.getAmountPoints() > 0
                ? Math.round(used * 10000.0 / budget.getAmountPoints()) / 100.0 : 0;
        return Map.of(
                "id", budget.getId(),
                "scopeType", budget.getScopeType(),
                "scopeValue", budget.getScopeValue(),
                "periodType", budget.getPeriodType(),
                "amountPoints", budget.getAmountPoints(),
                "alertThresholdPct", budget.getAlertThresholdPct(),
                "enabled", budget.getEnabled(),
                "usedPoints", used,
                "usedPct", pct);
    }

    private double costBetween(LocalDateTime since, Consumer<QueryWrapper<ModelCall>> scope) {
        QueryWrapper<ModelCall> wrapper = new QueryWrapper<>();
        wrapper.select("COALESCE(SUM(cost_points),0) AS cost").ge("created_at", since);
        if (scope != null) {
            scope.accept(wrapper);
        }
        Object value = modelCallMapper.selectMaps(wrapper).stream().findFirst()
                .map(m -> m.get("cost")).orElse(0);
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private java.util.function.Consumer<QueryWrapper<ModelCall>> budgetScopeFilter(Budget budget) {
        return wrapper -> {
            if (Budget.SCOPE_TENANT.equals(budget.getScopeType())) {
                wrapper.eq("tenant_id", budget.getScopeValue());
            } else if (Budget.SCOPE_SKILL.equals(budget.getScopeType())) {
                wrapper.eq("skill_code", budget.getScopeValue());
            }
        };
    }

    private static LocalDateTime monthStart() {
        return LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
    }
}
