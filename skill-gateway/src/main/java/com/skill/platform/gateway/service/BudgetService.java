package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Budget;
import com.skill.platform.gateway.dal.mapper.BudgetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 消耗预算 CRUD（月度）：scope 唯一校验；用量计算在 ConsumptionService。
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final BudgetMapper budgetMapper;

    public List<Budget> list() {
        return budgetMapper.selectList(new QueryWrapper<Budget>()
                .orderByAsc("scope_type").orderByAsc("id"));
    }

    public Budget create(Budget budget) {
        validate(budget);
        Long exists = budgetMapper.selectCount(new QueryWrapper<Budget>()
                .eq("scope_type", budget.getScopeType())
                .eq("scope_value", budget.getScopeValue() == null ? "" : budget.getScopeValue())
                .eq("period_type", budget.getPeriodType() == null ? "MONTHLY" : budget.getPeriodType()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "同 scope 同周期的预算已存在");
        }
        if (budget.getPeriodType() == null || budget.getPeriodType().isBlank()) {
            budget.setPeriodType("MONTHLY");
        }
        if (budget.getAlertThresholdPct() == null) {
            budget.setAlertThresholdPct(80);
        }
        budget.setEnabled(budget.getEnabled() == null ? 1 : budget.getEnabled());
        budgetMapper.insert(budget);
        return budget;
    }

    public Budget update(Long id, Budget patch) {
        Budget existing = mustGet(id);
        // 部分更新：仅校验并应用非空字段（scope 不可改，保证用量台账连续）
        if (patch.getAmountPoints() != null && patch.getAmountPoints() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "额度必须为正数");
        }
        if (patch.getAlertThresholdPct() != null
                && (patch.getAlertThresholdPct() <= 0 || patch.getAlertThresholdPct() > 100)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "告警阈值百分比应在 1-100");
        }
        if (patch.getAmountPoints() != null) {
            existing.setAmountPoints(patch.getAmountPoints());
        }
        if (patch.getAlertThresholdPct() != null) {
            existing.setAlertThresholdPct(patch.getAlertThresholdPct());
        }
        if (patch.getEnabled() != null) {
            existing.setEnabled(patch.getEnabled());
        }
        budgetMapper.updateById(existing);
        return existing;
    }

    public void delete(Long id) {
        mustGet(id);
        budgetMapper.deleteById(id);
    }

    public Budget updateStatus(Long id, Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enabled 仅允许 0/1");
        }
        Budget existing = mustGet(id);
        existing.setEnabled(enabled);
        budgetMapper.updateById(existing);
        return existing;
    }

    private Budget mustGet(Long id) {
        Budget budget = budgetMapper.selectById(id);
        if (budget == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "预算不存在");
        }
        return budget;
    }

    private void validate(Budget budget) {
        List<String> scopes = List.of(Budget.SCOPE_GLOBAL, Budget.SCOPE_TENANT, Budget.SCOPE_SKILL);
        if (budget.getScopeType() == null || !scopes.contains(budget.getScopeType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "scopeType 仅支持 " + String.join("/", scopes));
        }
        boolean needsValue = !Budget.SCOPE_GLOBAL.equals(budget.getScopeType());
        boolean hasValue = budget.getScopeValue() != null && !budget.getScopeValue().isBlank();
        boolean hasExistingValue = budget.getId() != null;
        if (needsValue && !hasValue && !hasExistingValue) {
            throw new BizException(ErrorCode.PARAM_INVALID, "TENANT/SKILL 预算必须指定 scopeValue");
        }
        if (budget.getAmountPoints() != null && budget.getAmountPoints() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "额度必须为正数");
        }
    }
}
