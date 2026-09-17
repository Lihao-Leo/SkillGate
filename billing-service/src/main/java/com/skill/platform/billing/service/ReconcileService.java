package com.skill.platform.billing.service;

import com.skill.platform.billing.dto.ReconcileReport;

/**
 * 对账兜底（技术方案 v6.5 §4.8）：扫描 FROZEN 超 24h 未结算的冻结单，
 * 先查任务状态再动钱。
 */
public interface ReconcileService {

    ReconcileReport reconcile();
}
