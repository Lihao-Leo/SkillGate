package com.skill.platform.billing;

import com.skill.platform.billing.common.BizException;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发不变量测试：
 * <ul>
 *   <li>冻结风暴：单行原子条件更新保证不花超（余额 100 × 30 并发冻结 10 → 恰好 10 成功）</li>
 *   <li>结算竞态：at-least-once 重复 settle 同一任务只扣一次</li>
 *   <li>同 taskId 并发冻结：唯一键 + 事务回滚保证只冻结一次</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class BillingConcurrencyTest {

    @Autowired
    private BillingService billing;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void concurrent_freeze_never_overspends() throws Exception {
        String appKey = "sk-conc-" + uniq();
        billing.ensureAccount(appKey, "tenant-" + uniq());
        billing.recharge(appKey, "tenant", 100, "order-" + uniq());

        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> jobs = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<Boolean>) () -> {
                        try {
                            billing.freeze("task-" + uniq() + "-" + i, appKey, 10, "storm");
                            return true;
                        } catch (BizException e) {
                            return false;
                        }
                    })
                    .toList();
            AtomicInteger wins = new AtomicInteger();
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                if (f.get()) {
                    wins.incrementAndGet();
                }
            }

            assertThat(wins.get()).isEqualTo(10);
            assertThat(billing.balance(appKey).balance()).isZero();
            assertThat(billing.balance(appKey).frozen()).isEqualTo(100);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrent_settle_same_task_charges_once() throws Exception {
        String appKey = "sk-conc-" + uniq();
        billing.ensureAccount(appKey, "tenant-" + uniq());
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();
        billing.freeze(taskId, appKey, 500, "accept");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<SettleResponse>> jobs = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<SettleResponse>) () -> billing.settle(taskId, 200))
                    .toList();
            List<SettleResponse> responses = new ArrayList<>();
            for (Future<SettleResponse> f : pool.invokeAll(jobs)) {
                responses.add(f.get());
            }

            assertThat(responses.stream().filter(r -> !r.alreadySettled())).hasSize(1);
            // 1000 → 冻结 500 → 结算 200、退 300 → 余额 800
            assertThat(billing.balance(appKey).balance()).isEqualTo(800);
            assertThat(billing.balance(appKey).frozen()).isZero();
            assertThat(billing.transactions(appKey, taskId, 1, 10).items())
                    .filteredOn(tx -> tx.type().equals("SETTLE")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrent_freeze_same_task_freezes_once() throws Exception {
        String appKey = "sk-conc-" + uniq();
        billing.ensureAccount(appKey, "tenant-" + uniq());
        billing.recharge(appKey, "tenant", 1000, "order-" + uniq());
        String taskId = "task-" + uniq();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<com.skill.platform.billing.dto.FreezeResponse>> jobs = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<com.skill.platform.billing.dto.FreezeResponse>) () ->
                            billing.freeze(taskId, appKey, 300, "race"))
                    .toList();
            List<com.skill.platform.billing.dto.FreezeResponse> responses = new ArrayList<>();
            for (Future<com.skill.platform.billing.dto.FreezeResponse> f : pool.invokeAll(jobs)) {
                responses.add(f.get());
            }

            assertThat(responses).hasSize(threads);
            // 无论哪个线程胜出：仅冻结一次，账户只迁移一次
            assertThat(billing.balance(appKey).frozen()).isEqualTo(300);
            assertThat(billing.balance(appKey).balance()).isEqualTo(700);
            assertThat(billing.transactions(appKey, taskId, 1, 10).items())
                    .filteredOn(tx -> tx.type().equals("HOLD")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
