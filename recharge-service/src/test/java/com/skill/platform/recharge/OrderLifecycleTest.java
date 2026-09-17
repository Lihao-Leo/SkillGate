package com.skill.platform.recharge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.recharge.channel.ChannelCrypto;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.mapper.PaymentNotifyLogMapper;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.dal.entity.PaymentNotifyLog;
import com.skill.platform.recharge.platform.SkillPlatformClient;
import com.skill.platform.recharge.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单全链路测试（§10.4 安全与幂等五条）：下单 → 回调验签 → 金额校验 →
 * 状态机幂等 → orderNo 幂等入账 → 首充签发与一次性 secret 展示。
 * 渠道为 MockChannel（真实 Bean）；执行平台客户端为 Mockito 替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OrderLifecycleTest {

    private static final String MOCK_SECRET = "dev-mock-channel-secret";
    private static final String JWT_SECRET = "dev-jwt-secret-change-me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RechargeOrderMapper orderMapper;
    @Autowired
    private PaymentNotifyLogMapper notifyLogMapper;

    @MockBean
    private SkillPlatformClient platformClient;

    private final AtomicInteger issueCounter = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(platformClient.issueKey(any())).thenAnswer(inv -> {
            int seq = issueCounter.incrementAndGet();
            return new SkillPlatformClient.IssuedKey(
                    "sk-issued-" + seq + "-" + UUID.randomUUID().toString().substring(0, 6),
                    "sk-secret-issued-" + seq);
        });
        when(platformClient.recharge(any(), anyLong(), any()))
                .thenAnswer(inv -> new SkillPlatformClient.Recharged(
                        "tx_" + uniq(), inv.getArgument(1), 0, false));
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final java.util.concurrent.atomic.AtomicLong USER_SEQ =
            new java.util.concurrent.atomic.AtomicLong(1_000_000);

    /** 建登录用户（JWT 内的用户标识；订单按 userId 隔离），返回 userId */
    private long seedUser() {
        return USER_SEQ.incrementAndGet();
    }

    private String tokenOf(long userId) {
        return JwtUtil.issue(JWT_SECRET, userId, Instant.now().getEpochSecond() + 3600);
    }

    private String createOrder(long userId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/orders")
                        .header("Authorization", "Bearer " + tokenOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").exists())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
    }

    /** Mock 渠道签名回调请求体 */
    private String mockNotifyBody(String orderNo, long amountFen) {
        return "{\"outTradeNo\":\"" + orderNo + "\",\"tradeNo\":\"mock-trade-" + uniq()
                + "\",\"paidAmountFen\":" + amountFen + "}";
    }

    @Test
    void full_lifecycle_create_notify_credit_with_one_time_secret() throws Exception {
        long userId = seedUser();
        String orderNo = createOrder(userId,
                "{\"channel\":\"MOCK\",\"skuId\":\"sku_550\"}");

        RechargeOrder order = loadOrder(orderNo);
        assertThat(order.getStatus()).isEqualTo("PAYING");
        assertThat(order.getCodeUrl()).isEqualTo("mockpay://qr/" + orderNo);
        assertThat(order.getAmountFen()).isEqualTo(5000L);
        assertThat(order.getSkuPoints()).isEqualTo(550);

        // 回调：验签通过 + 金额一致 → PAID → 首充签发 + orderNo 幂等入账 → CREDITED
        String body = mockNotifyBody(orderNo, 5000);
        MvcResult notify = mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", ChannelCrypto.hmacSha256(MOCK_SECRET, body))
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(notify.getResponse().getContentAsString()).contains("SUCCESS");

        assertThat(loadOrder(orderNo).getStatus()).isEqualTo("CREDITED");
        verify(platformClient, times(1)).issueKey("recharge-user-" + userId);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> pointsCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> orderCaptor = ArgumentCaptor.forClass(String.class);
        verify(platformClient).recharge(keyCaptor.capture(), pointsCaptor.capture(), orderCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("sk-issued-");
        assertThat(pointsCaptor.getValue()).isEqualTo(550L);
        assertThat(orderCaptor.getValue()).isEqualTo(orderNo);

        // 订单查询：CREDITED + 一次性 key 展示（首次含 secret，二次不含）
        MvcResult firstView = mockMvc.perform(get("/api/user/orders/{o}", orderNo)
                        .header("Authorization", "Bearer " + tokenOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"))
                .andExpect(jsonPath("$.data.creditedPoints").value(550))
                .andReturn();
        assertThat(objectMapper.readTree(firstView.getResponse().getContentAsString())
                .path("data").path("newlyIssuedKey").path("appSecret").asText())
                .startsWith("sk-secret-issued-");

        mockMvc.perform(get("/api/user/orders/{o}", orderNo)
                        .header("Authorization", "Bearer " + tokenOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyIssuedKey").doesNotExist());

        // 重复回调：应答成功且不二次入账（回调幂等）
        mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", ChannelCrypto.hmacSha256(MOCK_SECRET, body))
                        .content(body))
                .andExpect(status().isOk());
        verify(platformClient, times(1)).recharge(any(), anyLong(), any());

        // 留档：两条 notify log，第二条为幂等重复
        var logs = notifyLogMapper.selectList(new LambdaQueryWrapper<PaymentNotifyLog>()
                .eq(PaymentNotifyLog::getOrderNo, orderNo));
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getVerifyMessage()).contains("幂等");
    }

    @Test
    void notify_amount_mismatch_rejected_and_logged() throws Exception {
        long userId = seedUser();
        String orderNo = createOrder(userId, "{\"channel\":\"MOCK\",\"skuId\":\"sku_100\"}");
        String body = mockNotifyBody(orderNo, 1L); // 应付 1000 分

        mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", ChannelCrypto.hmacSha256(MOCK_SECRET, body))
                        .content(body))
                .andExpect(status().isInternalServerError());

        assertThat(loadOrder(orderNo).getStatus()).isEqualTo("PAYING");
        verify(platformClient, never()).recharge(any(), anyLong(), any());
        PaymentNotifyLog logEntry = notifyLogMapper.selectOne(new LambdaQueryWrapper<PaymentNotifyLog>()
                .eq(PaymentNotifyLog::getOrderNo, orderNo).last("LIMIT 1"));
        assertThat(logEntry.getVerifyMessage()).contains("金额不符");
    }

    @Test
    void notify_invalid_signature_rejected_and_archived() throws Exception {
        long userId = seedUser();
        String orderNo = createOrder(userId, "{\"channel\":\"MOCK\",\"skuId\":\"sku_100\"}");
        String body = mockNotifyBody(orderNo, 1000L);

        mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", "deadbeef")
                        .content(body))
                .andExpect(status().isInternalServerError());

        assertThat(loadOrder(orderNo).getStatus()).isEqualTo("PAYING");
        // 原始报文仍全量留档（验签失败，order_no 为空，按 raw_body 检索）
        PaymentNotifyLog logEntry = notifyLogMapper.selectOne(new LambdaQueryWrapper<PaymentNotifyLog>()
                .like(PaymentNotifyLog::getRawBody, orderNo)
                .orderByDesc(PaymentNotifyLog::getId)
                .last("LIMIT 1"));
        assertThat(logEntry).isNotNull();
        assertThat(logEntry.getSignatureValid()).isZero();
    }

    @Test
    void second_order_reuses_primary_key_without_new_issue() throws Exception {
        long userId = seedUser();
        String first = createOrder(userId, "{\"channel\":\"MOCK\",\"skuId\":\"sku_100\"}");
        String body1 = mockNotifyBody(first, 1000L);
        mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", ChannelCrypto.hmacSha256(MOCK_SECRET, body1))
                        .content(body1)).andExpect(status().isOk());

        String second = createOrder(userId, "{\"channel\":\"MOCK\",\"skuId\":\"sku_100\"}");
        String body2 = mockNotifyBody(second, 1000L);
        mockMvc.perform(post("/api/notify/MOCK/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Mock-Signature", ChannelCrypto.hmacSha256(MOCK_SECRET, body2))
                        .content(body2)).andExpect(status().isOk());

        // 同用户后续充值默认入主 key 账户：不再签发新 key，两单各入 100 点（sku_100）
        verify(platformClient, times(1)).issueKey(any());
        verify(platformClient, times(2)).recharge(any(), eq(100L), any());
    }

    @Test
    void order_view_rejects_foreign_user() throws Exception {
        long owner = seedUser();
        String orderNo = createOrder(owner, "{\"channel\":\"MOCK\",\"skuId\":\"sku_100\"}");
        long stranger = seedUser();
        mockMvc.perform(get("/api/user/orders/{o}", orderNo)
                        .header("Authorization", "Bearer " + tokenOf(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void custom_amount_order_created() throws Exception {
        long userId = seedUser();
        String orderNo = createOrder(userId,
                "{\"channel\":\"MOCK\",\"customAmountFen\":2500}");

        RechargeOrder order = loadOrder(orderNo);
        assertThat(order.getAmountFen()).isEqualTo(2500L);
        // 1 分 = 1 点，无赠送
        assertThat(order.getSkuPoints()).isEqualTo(2500);
    }

    @Test
    void unknown_sku_and_bad_amount_rejected() throws Exception {
        long userId = seedUser();
        mockMvc.perform(post("/api/user/orders")
                        .header("Authorization", "Bearer " + tokenOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"MOCK\",\"skuId\":\"sku_ghost\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/user/orders")
                        .header("Authorization", "Bearer " + tokenOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"MOCK\",\"customAmountFen\":50}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/user/orders")
                        .header("Authorization", "Bearer " + tokenOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"GHOST\",\"customAmountFen\":1000}"))
                .andExpect(status().isBadRequest());
    }

    private RechargeOrder loadOrder(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, orderNo).last("LIMIT 1"));
    }
}
