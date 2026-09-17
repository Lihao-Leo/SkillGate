package com.skill.platform.billing.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.billing.dal.mapper.BillingHoldMapper;
import com.skill.platform.billing.dal.mapper.PlatformExecutionMapper;
import com.skill.platform.billing.dto.OverdueHold;
import com.skill.platform.billing.dto.ReconcileReport;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.service.AlertNotifier;
import com.skill.platform.billing.service.ReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对账实现（每 5min 由调度触发；也可手动触发）。
 *
 * <p>三种成因三条路径（§4.8）：
 * <ol>
 *   <li>任务已终态 → 结算消息丢失 → 按规则补 settle / release + INFO 告警</li>
 *   <li>任务非终态（PENDING/RUNNING/CANCELLING）→ 任务从未执行 →
 *       先置 execution CANCELLED（迟到消息被不变量挡掉）再 release + HIGH 告警</li>
 *   <li>任务记录不存在（受理侧已回滚但 release 失败的残留）→ release + HIGH 告警</li>
 * </ol>
 *
 * <p>补结算金额：PER_EXECUTION = 单价 × min(实际产物数, expectedCount)（pricing 快照复算）；
 * METERED 对账时无 usage 数据源，V1 口径全额释放并 WARN 告警（宁可少收，人工复核）。
 */
@Slf4j
@Service
public class ReconcileServiceImpl implements ReconcileService {

    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final BillingHoldMapper holdMapper;
    private final PlatformExecutionMapper platformExecutionMapper;
    private final com.skill.platform.billing.service.BillingService billingService;
    private final AlertNotifier alertNotifier;
    private final ObjectMapper objectMapper;

    @Value("${skill-billing.reconcile.overdue-hours:24}")
    private int overdueHours;

    /** platform 库名：prod=skill_platform（分库）/ dev=dev-skill（单库联调） */
    @Value("${skill-billing.platform-schema:skill_platform}")
    private String platformSchema;

    public ReconcileServiceImpl(BillingHoldMapper holdMapper,
                                PlatformExecutionMapper platformExecutionMapper,
                                com.skill.platform.billing.service.BillingService billingService,
                                AlertNotifier alertNotifier,
                                ObjectMapper objectMapper) {
        this.holdMapper = holdMapper;
        this.platformExecutionMapper = platformExecutionMapper;
        this.billingService = billingService;
        this.alertNotifier = alertNotifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReconcileReport reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(overdueHours);
        List<OverdueHold> overdue = holdMapper.selectOverdue(cutoff, platformSchema);
        int settled = 0;
        int released = 0;
        int cancelled = 0;
        int orphanReleased = 0;
        List<String> alerts = new ArrayList<>();

        for (OverdueHold hold : overdue) {
            try {
                if (hold.execStatus() == null) {
                    billingService.releaseByTask(hold.taskId());
                    orphanReleased++;
                    String msg = "orphan hold released: taskId=" + hold.taskId()
                            + ", holdId=" + hold.holdId() + ", amount=" + hold.amount();
                    alerts.add("[HIGH] " + msg);
                    alertNotifier.notify("HIGH", "对账：冻结单无任务记录，已释放", msg);
                } else if (TERMINAL_STATUSES.contains(hold.execStatus())) {
                    if ("SETTLED".equals(settledOrReleased(hold, alerts))) {
                        settled++;
                    } else {
                        released++;
                    }
                } else {
                    // 非终态：先置 CANCELLED 止损，再释放
                    int updated = platformExecutionMapper.cancelIfNotTerminal(platformSchema, hold.taskId());
                    if (updated == 1) {
                        billingService.releaseByTask(hold.taskId());
                        cancelled++;
                        String msg = "execution never started, cancelled and released: taskId="
                                + hold.taskId() + ", amount=" + hold.amount();
                        alerts.add("[HIGH] " + msg);
                        alertNotifier.notify("HIGH", "对账：任务未执行，已取消并释放冻结", msg);
                    } else {
                        log.info("reconcile race: execution turned terminal before cancel, taskId={}",
                                hold.taskId());
                    }
                }
            } catch (Exception e) {
                log.error("reconcile item failed: taskId={}", hold.taskId(), e);
                alerts.add("[HIGH] reconcile item failed: taskId=" + hold.taskId() + ": " + e.getMessage());
                alertNotifier.notify("HIGH", "对账：单条处理失败", "taskId=" + hold.taskId() + ": " + e.getMessage());
            }
        }
        ReconcileReport report = new ReconcileReport(overdue.size(), settled, released,
                cancelled, orphanReleased, alerts);
        if (overdue.size() > 0) {
            log.warn("reconcile finished: scanned={}, settled={}, released={}, cancelled={}, orphan={}",
                    report.scanned(), report.settled(), report.released(),
                    report.cancelled(), report.orphanReleased());
            alertNotifier.notify("INFO", "对账完成",
                    "scanned=" + report.scanned() + ", settled=" + report.settled()
                            + ", released=" + report.released() + ", cancelled=" + report.cancelled()
                            + ", orphan=" + report.orphanReleased());
        }
        return report;
    }

    /**
     * 已终态：SUCCEEDED 按 pricing 快照补结算（METERED 释放）；FAILED/CANCELLED 全额释放。
     *
     * @return "SETTLED"（补结算）或 "RELEASED"（全额释放）
     */
    private String settledOrReleased(OverdueHold hold, List<String> alerts) {
        if (!"SUCCEEDED".equals(hold.execStatus())) {
            billingService.releaseByTask(hold.taskId());
            return "RELEASED";
        }
        if (isMetered(hold)) {
            billingService.releaseByTask(hold.taskId());
            String msg = "metered task overdue without usage source, released: taskId=" + hold.taskId()
                    + ", amount=" + hold.amount();
            alerts.add("[WARN] " + msg);
            alertNotifier.notify("WARN", "对账：METERED 无用量数据源，已全额释放（人工复核）", msg);
            return "RELEASED";
        }
        long charged = computePerExecutionCharge(hold);
        SettleResponse resp = billingService.settle(hold.taskId(), charged);
        if (!resp.alreadySettled()) {
            String msg = "settle message lost, compensated: taskId=" + hold.taskId()
                    + ", charged=" + charged + ", hold=" + hold.holdId();
            alerts.add("[INFO] " + msg);
            alertNotifier.notify("INFO", "对账：结算消息丢失，已补结算", msg);
        }
        return "SETTLED";
    }

    private boolean isMetered(OverdueHold hold) {
        try {
            JsonNode snapshot = objectMapper.readTree(hold.pricingSnapshot() == null ? "{}" : hold.pricingSnapshot());
            return "METERED".equals(snapshot.path("mode").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * PER_EXECUTION 补结算：单价 × min(实际产物数, expectedCount)，以 execution.pricing_snapshot 复算。
     */
    private long computePerExecutionCharge(OverdueHold hold) {
        long unitPoints = 0;
        try {
            JsonNode snapshot = objectMapper.readTree(hold.pricingSnapshot() == null ? "{}" : hold.pricingSnapshot());
            unitPoints = snapshot.path("points").asLong(0);
        } catch (Exception e) {
            log.warn("parse pricing snapshot failed, charge 0: taskId={}", hold.taskId(), e);
            return 0;
        }
        int artifactCount = platformExecutionMapper.countArtifacts(platformSchema, hold.taskId());
        int expected = hold.expectedCount() == null ? 1 : hold.expectedCount();
        long billable = Math.max(0, Math.min(artifactCount, expected));
        return unitPoints * billable;
    }
}
