package com.skill.platform.billing.service.impl;

import com.skill.platform.billing.dto.ReconcileReport;
import com.skill.platform.billing.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账调度（默认每 5min）：billing 发布前建议先手动跑一轮清零悬空 hold（§8.3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "skill-billing.reconcile", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReconcileScheduler {

    private final ReconcileService reconcileService;

    @Scheduled(cron = "${skill-billing.reconcile.cron:0 */5 * * * ?}")
    public void run() {
        try {
            ReconcileReport report = reconcileService.reconcile();
            if (report.scanned() > 0) {
                log.info("scheduled reconcile: {}", report);
            }
        } catch (Exception e) {
            log.error("scheduled reconcile failed", e);
        }
    }
}
