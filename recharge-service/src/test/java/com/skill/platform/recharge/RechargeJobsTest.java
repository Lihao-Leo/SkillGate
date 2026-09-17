package com.skill.platform.recharge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.channel.MockChannel;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.platform.SkillPlatformClient;
import com.skill.platform.recharge.service.CreditService;
import com.skill.platform.recharge.service.jobs.CreditRetryJob;
import com.skill.platform.recharge.service.jobs.OrderExpireJob;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底任务测试（§10.4）：入账重试（退避推进）、查单兜底（补单 / 关单过期）。
 */
@SpringBootTest
@ActiveProfiles("local")
class RechargeJobsTest {

    @Autowired
    private RechargeOrderMapper orderMapper;
    @Autowired
    private CreditService creditService;
    @Autowired
    private CreditRetryJob creditRetryJob;
    @Autowired
    private OrderExpireJob orderExpireJob;

    @MockBean
    private SkillPlatformClient platformClient;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private RechargeOrder seedOrder(String status, boolean expired) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo("R" + uniq());
        order.setUserId(900L + uniq().hashCode() % 1000);
        order.setChannel("MOCK");
        order.setSkuPoints(100);
        order.setAmountFen(1000L);
        order.setStatus(status);
        order.setExpireAt(LocalDateTime.now().plusMinutes(expired ? -1 : 10));
        if ("PAID".equals(status)) {
            order.setPaidAmountFen(1000L);
            order.setPaidAt(LocalDateTime.now());
        }
        order.setRetryCount(0);
        orderMapper.insert(order);
        return order;
    }

    @Test
    void credit_failure_schedules_backoff_then_retry_job_completes() {
        when(platformClient.issueKey(any())).thenReturn(
                new SkillPlatformClient.IssuedKey("sk-retry-" + uniq(), "sk-secret-x"));
        when(platformClient.recharge(any(), anyLong(), any())).thenReturn(
                new SkillPlatformClient.Recharged("tx", 100, 100, false));
        // 首推失败：留 PAID + 退避计划
        Mockito.doThrow(new IllegalStateException("platform down"))
                .when(platformClient).recharge(any(), anyLong(), any());
        RechargeOrder order = seedOrder("PAID", false);

        boolean pushed = creditService.credit(order.getOrderNo());
        assertThat(pushed).isFalse();
        RechargeOrder stuck = loadOrder(order.getOrderNo());
        assertThat(stuck.getStatus()).isEqualTo("PAID");
        assertThat(stuck.getRetryCount()).isEqualTo(1);
        assertThat(stuck.getNextRetryAt()).isAfter(LocalDateTime.now());
        assertThat(stuck.getLastError()).contains("platform down");

        // 退避未到：重试任务不处理（next_retry_at 在未来）
        creditRetryJob.retryStuckCredits();
        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("PAID");

        // 到点重推：平台恢复 → CREDITED（orderNo 幂等消化此前的失败重放）
        Mockito.doReturn(new SkillPlatformClient.Recharged("tx", 100, 100, false))
                .when(platformClient).recharge(any(), anyLong(), any());
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, order.getOrderNo())
                .set(RechargeOrder::getNextRetryAt, LocalDateTime.now().minusMinutes(1)));
        creditRetryJob.retryStuckCredits();
        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("CREDITED");
    }

    @Test
    void expired_unpaid_order_closed_and_expired() {
        RechargeOrder order = seedOrder("PAYING", true);
        MockChannel.markPaid(order.getOrderNo(), false);

        orderExpireJob.reconcileExpiring();

        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("EXPIRED");
        verify(platformClient, never()).recharge(any(), anyLong(), any());
    }

    @Test
    void expired_but_channel_paid_order_compensated_to_credited() {
        // 回调丢失场景：渠道已支付 → 查单补 PAID + 入账（先查成因再动钱）
        when(platformClient.issueKey(any())).thenReturn(
                new SkillPlatformClient.IssuedKey("sk-comp-" + uniq(), "sk-secret-x"));
        when(platformClient.recharge(any(), anyLong(), any())).thenReturn(
                new SkillPlatformClient.Recharged("tx", 100, 100, false));
        RechargeOrder order = seedOrder("PAYING", true);
        MockChannel.markPaid(order.getOrderNo(), true);

        orderExpireJob.reconcileExpiring();

        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("CREDITED");
        verify(platformClient, times(1)).recharge(any(), eq(100L), any());
    }

    @Test
    void near_expiry_unpaid_order_waits_for_next_round() {
        // 临期（未过期）且未支付：仅查询，不动状态
        RechargeOrder order = seedOrder("PAYING", false);
        // 把 expire_at 压到 2min 内（临期窗口）
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, order.getOrderNo())
                .set(RechargeOrder::getExpireAt, LocalDateTime.now().plusMinutes(1)));

        orderExpireJob.reconcileExpiring();

        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("PAYING");
    }

    @Test
    void created_order_without_channel_trade_expires_directly() {
        RechargeOrder order = seedOrder("CREATED", true);
        orderExpireJob.reconcileExpiring();
        assertThat(loadOrder(order.getOrderNo()).getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void duplicate_notify_and_retry_never_double_credits() {
        // PAID 已 CREDITED：credit 幂等直接返回，不再外调
        when(platformClient.issueKey(any())).thenReturn(
                new SkillPlatformClient.IssuedKey("sk-dup-" + uniq(), "sk-secret-x"));
        RechargeOrder order = seedOrder("PAID", false);
        orderMapper.markCredited(order.getOrderNo());

        assertThat(creditService.credit(order.getOrderNo())).isFalse();
        verify(platformClient, never()).recharge(any(), anyLong(), any());
    }

    private RechargeOrder loadOrder(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, orderNo).last("LIMIT 1"));
    }
}
