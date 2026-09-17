package com.skill.platform.billing;

import com.skill.platform.billing.common.BizException;
import com.skill.platform.billing.common.ErrorCode;
import com.skill.platform.billing.dto.BalanceView;
import com.skill.platform.billing.dto.FreezeResponse;
import com.skill.platform.billing.dto.PageView;
import com.skill.platform.billing.dto.RechargeResult;
import com.skill.platform.billing.dto.ReleaseResponse;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.dto.TransactionView;
import com.skill.platform.billing.service.BillingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计费核心闭环测试（技术方案 §4.8 / 用例 TC-BIL-001~005）：
 * 冻结 → 结算/释放 → 不可变流水 → 幂等。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingCoreTest {

    @Autowired
    private BillingService billing;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String newAccount() {
        String appKeyId = "sk-test-" + uniq();
        billing.ensureAccount(appKeyId, "tenant-" + uniq());
        return appKeyId;
    }

    @Test
    void freeze_moves_balance_to_frozen_and_writes_hold_transaction() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();

        FreezeResponse frozen = billing.freeze(taskId, appKey, 300, "accept");

        assertThat(frozen.alreadyFrozen()).isFalse();
        assertThat(frozen.amount()).isEqualTo(300);

        BalanceView balance = billing.balance(appKey);
        assertThat(balance.balance()).isEqualTo(700);
        assertThat(balance.frozen()).isEqualTo(300);

        List<TransactionView> txs = billing.transactions(appKey, taskId, 1, 10).items();
        assertThat(txs).extracting(TransactionView::type).containsExactly("HOLD");
        assertThat(txs.get(0).amount()).isEqualTo(-300);
        assertThat(txs.get(0).balanceAfter()).isEqualTo(700);
    }

    @Test
    void freeze_insufficient_balance_fails_fast_without_side_effects() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 50, "order-" + uniq());
        String taskId = "task-" + uniq();

        assertThatThrownBy(() -> billing.freeze(taskId, appKey, 100, "accept"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        // 不建 hold、不动账
        assertThat(billing.holdOf(taskId)).isNull();
        assertThat(billing.balance(appKey).balance()).isEqualTo(50);
        assertThat(billing.balance(appKey).frozen()).isEqualTo(0);
        assertThat(billing.transactions(appKey, null, 1, 10).items())
                .noneMatch(tx -> tx.type().equals("HOLD"));
    }

    @Test
    void freeze_unknown_account_rejected() {
        assertThatThrownBy(() -> billing.freeze("task-" + uniq(), "sk-ghost-" + uniq(), 10, "accept"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void freeze_idempotent_by_task_id() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 500, "order-" + uniq());
        String taskId = "task-" + uniq();

        FreezeResponse first = billing.freeze(taskId, appKey, 200, "accept");
        FreezeResponse second = billing.freeze(taskId, appKey, 200, "retry");

        assertThat(second.alreadyFrozen()).isTrue();
        assertThat(second.holdId()).isEqualTo(first.holdId());
        assertThat(billing.balance(appKey).frozen()).isEqualTo(200);
    }

    @Test
    void settle_charges_actual_and_refunds_difference() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, 300, "accept");

        SettleResponse response = billing.settle(taskId, 70);

        assertThat(response.pointsCharged()).isEqualTo(70);
        assertThat(response.status()).isEqualTo("SETTLED");
        BalanceView balance = billing.balance(appKey);
        // 1000 → 冻结 300（余额 700）→ 结算 70、退 230 → 余额 930
        assertThat(balance.balance()).isEqualTo(930);
        assertThat(balance.frozen()).isZero();

        PageView<TransactionView> page = billing.transactions(appKey, taskId, 1, 10);
        assertThat(page.items()).extracting(TransactionView::type).containsExactly("SETTLE", "HOLD");
        assertThat(page.items().get(0).amount()).isEqualTo(-70);
        assertThat(page.items().get(0).balanceAfter()).isEqualTo(930);
    }

    @Test
    void settle_full_refund_on_zero_actual() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, 300, "accept");

        billing.settle(taskId, 0);

        assertThat(billing.balance(appKey).balance()).isEqualTo(1000);
        assertThat(billing.balance(appKey).frozen()).isEqualTo(0);
    }

    @Test
    void settle_never_exceeds_frozen_amount() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, 100, "accept");

        SettleResponse response = billing.settle(taskId, 999);

        assertThat(response.pointsCharged()).isEqualTo(100);
        assertThat(billing.balance(appKey).frozen()).isEqualTo(0);
        assertThat(billing.balance(appKey).balance()).isEqualTo(900);
    }

    @Test
    void settle_duplicate_message_is_idempotent() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, 300, "accept");
        billing.settle(taskId, 80);

        SettleResponse duplicate = billing.settle(taskId, 80);

        assertThat(duplicate.alreadySettled()).isTrue();
        assertThat(duplicate.pointsCharged()).isEqualTo(80);
        // 1000 → 冻结 300 → 结算 80、退 220 → 余额 920
        assertThat(billing.balance(appKey).balance()).isEqualTo(920);
        assertThat(billing.transactions(appKey, taskId, 1, 10).items())
                .filteredOn(tx -> tx.type().equals("SETTLE")).hasSize(1);
    }

    @Test
    void settle_missing_hold_rejected() {
        assertThatThrownBy(() -> billing.settle("task-ghost-" + uniq(), 10))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void release_refunds_full_amount_and_is_idempotent() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        FreezeResponse frozen = billing.freeze(taskId, appKey, 300, "accept");

        ReleaseResponse released = billing.release(frozen.holdId());

        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(billing.balance(appKey).balance()).isEqualTo(1000);
        assertThat(billing.balance(appKey).frozen()).isEqualTo(0);

        ReleaseResponse again = billing.releaseByTask(taskId);
        assertThat(again.alreadyReleased()).isTrue();
        assertThat(billing.transactions(appKey, taskId, 1, 10).items())
                .filteredOn(tx -> tx.type().equals("RELEASE")).hasSize(1);
    }

    @Test
    void recharge_is_idempotent_by_order_no() {
        String appKey = newAccount();
        String orderNo = "ord-" + uniq();

        RechargeResult first = billing.recharge(appKey, "tenant", 500, orderNo);
        assertThat(first.alreadyCredited()).isFalse();
        assertThat(first.points()).isEqualTo(500);
        assertThat(first.balance()).isEqualTo(500);

        // 重复回调 / 重试任务重放：返回已入账流水，不二次入账
        RechargeResult duplicate = billing.recharge(appKey, "tenant", 500, orderNo);
        assertThat(duplicate.alreadyCredited()).isTrue();
        assertThat(duplicate.txId()).isEqualTo(first.txId());
        assertThat(billing.balance(appKey).balance()).isEqualTo(500);
        assertThat(billing.transactions(appKey, null, 1, 10).items())
                .filteredOn(tx -> tx.type().equals("RECHARGE")).hasSize(1);
    }

    @Test
    void recharge_and_adjust_maintain_invariants() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant-a", 100, "order-1-" + uniq());

        BalanceView adjustedUp = billing.adjust(appKey, 50, "marketing bonus");
        assertThat(adjustedUp.balance()).isEqualTo(150);

        BalanceView adjustedDown = billing.adjust(appKey, -30, "correction");
        assertThat(adjustedDown.balance()).isEqualTo(120);

        assertThatThrownBy(() -> billing.adjust(appKey, -1000, "overdraw"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.BILLING_VALIDATION_FAILED));
        assertThat(billing.balance(appKey).balance()).isEqualTo(120);
    }

    @Test
    void balance_of_unknown_account_is_zero() {
        String appKey = "sk-none-" + uniq();
        BalanceView balance = billing.balance(appKey);
        assertThat(balance.balance()).isZero();
        assertThat(balance.frozen()).isZero();
    }

    @Test
    void transactions_support_task_filter_and_paging() {
        String appKey = newAccount();
        billing.recharge(appKey, "tenant", 1000, "o1-" + uniq());
        String taskA = "task-" + uniq();
        billing.freeze(taskA, appKey, 100, "accept");
        billing.settle(taskA, 100);

        PageView<TransactionView> filtered = billing.transactions(appKey, taskA, 1, 10);
        assertThat(filtered.items()).extracting(TransactionView::type).containsExactly("SETTLE", "HOLD");
        assertThat(filtered.total()).isEqualTo(2);

        PageView<TransactionView> paged = billing.transactions(appKey, null, 1, 2);
        assertThat(paged.items()).hasSize(2);
        assertThat(paged.total()).isGreaterThanOrEqualTo(3);
    }
}
