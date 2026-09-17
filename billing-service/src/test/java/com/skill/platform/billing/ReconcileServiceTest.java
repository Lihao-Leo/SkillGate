package com.skill.platform.billing;

import com.skill.platform.billing.dto.ReconcileReport;
import com.skill.platform.billing.service.BillingService;
import com.skill.platform.billing.service.ReconcileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账兜底测试（技术方案 §4.8 / 用例 TC-BIL-008、TC-BIL-009）：
 * FROZEN 超 24h → 先查任务状态再动钱，三种成因三条路径。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReconcileServiceTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private BillingService billing;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 造一笔超期 FROZEN hold：freeze 后把 created_at 回拨 48h。 */
    private String seedOverdueHold(long amount) {
        String appKey = "sk-rec-" + uniq();
        billing.ensureAccount(appKey, "tenant-" + uniq());
        billing.recharge(appKey, "tenant", amount, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, amount, "accept");
        jdbcTemplate.update("UPDATE billing_hold SET created_at = DATEADD('HOUR', -48, created_at) "
                + "WHERE task_id = ?", taskId);
        return taskId + "|" + appKey;
    }

    private void seedExecution(String taskId, String status, Integer expectedCount, String pricingSnapshot) {
        jdbcTemplate.update(
                "INSERT INTO skill_platform.execution (task_id, status, expected_count, pricing_snapshot) "
                        + "VALUES (?, ?, ?, ?)", taskId, status, expectedCount, pricingSnapshot);
    }

    private void seedArtifacts(String taskId, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO skill_platform.artifact (task_id, type, file_size) VALUES (?, 'video', 100)",
                    taskId);
        }
    }

    @Test
    void overdue_hold_with_succeeded_execution_settles_by_pricing_snapshot() {
        String[] key = seedOverdueHold(300).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        seedExecution(taskId, "SUCCEEDED", 10, "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        seedArtifacts(taskId, 7); // 实际 7 个 < expected 10 → 按 min=7 计费 70

        ReconcileReport report = reconcileService.reconcile();

        assertThat(report.settled()).isGreaterThanOrEqualTo(1);
        assertThat(billing.holdOf(taskId).status()).isEqualTo("SETTLED");
        assertThat(billing.holdOf(taskId).settledAmount()).isEqualTo(70);
        // 充值 300 → 冻结 300（余额 0）→ 补结算 70、退 230
        assertThat(billing.balance(appKey).balance()).isEqualTo(230);
        assertThat(billing.balance(appKey).frozen()).isZero();
    }

    @Test
    void overdue_hold_with_terminal_failure_releases_full_amount() {
        String[] key = seedOverdueHold(300).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        seedExecution(taskId, "FAILED", null, "{\"mode\":\"PER_EXECUTION\",\"points\":10}");

        reconcileService.reconcile();

        assertThat(billing.holdOf(taskId).status()).isEqualTo("RELEASED");
        assertThat(billing.balance(appKey).balance()).isEqualTo(300);
        assertThat(billing.balance(appKey).frozen()).isZero();
    }

    @Test
    void overdue_hold_with_metered_success_releases_and_alerts_for_manual_review() {
        String[] key = seedOverdueHold(500).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        seedExecution(taskId, "SUCCEEDED", null, "{\"mode\":\"METERED\",\"capPoints\":500}");

        ReconcileReport report = reconcileService.reconcile();

        assertThat(billing.holdOf(taskId).status()).isEqualTo("RELEASED");
        assertThat(billing.balance(appKey).balance()).isEqualTo(500);
        assertThat(report.alerts()).anyMatch(a -> a.contains("[WARN]"));
    }

    @Test
    void overdue_hold_with_running_execution_cancels_then_releases_with_high_alert() {
        String[] key = seedOverdueHold(200).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        seedExecution(taskId, "RUNNING", null, "{\"mode\":\"PER_EXECUTION\",\"points\":10}");

        ReconcileReport report = reconcileService.reconcile();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM skill_platform.execution WHERE task_id = ?", String.class, taskId);
        assertThat(status).isEqualTo("CANCELLED");
        assertThat(billing.holdOf(taskId).status()).isEqualTo("RELEASED");
        assertThat(billing.balance(appKey).balance()).isEqualTo(200);
        assertThat(report.alerts()).anyMatch(a -> a.contains("[HIGH]"));
    }

    @Test
    void overdue_hold_without_execution_record_releases_as_orphan() {
        String[] key = seedOverdueHold(120).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        // 不建 execution 记录：模拟受理侧已回滚但 release 失败的残留

        ReconcileReport report = reconcileService.reconcile();

        assertThat(billing.holdOf(taskId).status()).isEqualTo("RELEASED");
        assertThat(billing.balance(appKey).balance()).isEqualTo(120);
        assertThat(report.alerts()).anyMatch(a -> a.contains("[HIGH]"));
    }

    @Test
    void already_settled_hold_is_not_touched_again() {
        String[] key = seedOverdueHold(300).split("\\|");
        String taskId = key[0];
        String appKey = key[1];
        seedExecution(taskId, "SUCCEEDED", 5, "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        seedArtifacts(taskId, 5);
        // worker 已结算（消息未丢但 hold created_at 被回拨的极端场景）：对账幂等
        billing.settle(taskId, 50);

        reconcileService.reconcile();

        assertThat(billing.holdOf(taskId).settledAmount()).isEqualTo(50);
        assertThat(billing.transactions(appKey, taskId, 1, 10).items())
                .filteredOn(tx -> tx.type().equals("SETTLE")).hasSize(1);
    }
}
