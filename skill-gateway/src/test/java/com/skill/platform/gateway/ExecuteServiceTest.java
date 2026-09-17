package com.skill.platform.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.entity.Material;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillVersion;
import com.skill.platform.gateway.dal.mapper.AppKeyMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.dal.mapper.MaterialMapper;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.dal.mapper.SkillVersionMapper;
import com.skill.platform.gateway.infra.ExecuteMessageProducer;
import com.skill.platform.gateway.infra.ObjectStorage;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.ExecuteDtos.ExecuteRequest;
import com.skill.platform.gateway.service.ExecuteDtos.ExecuteResult;
import com.skill.platform.gateway.service.ExecuteDtos.MaterialInput;
import com.skill.platform.gateway.service.ExecuteService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受理五关全分支测试（§4.2 / TC-EXE-001~012 单测可覆盖部分）。
 * BillingClient 与 MQ 生产者为 Mockito 替身；OSS/KV 为进程内实现；时钟固定保证 QPS 窗口确定性。
 */
@SpringBootTest
@ActiveProfiles("local")
class ExecuteServiceTest {

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private ExecuteService executeService;
    @Autowired
    private AppKeyMapper appKeyMapper;
    @Autowired
    private SkillMapper skillMapper;
    @Autowired
    private SkillVersionMapper skillVersionMapper;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private MaterialMapper materialMapper;
    @Autowired
    private ObjectStorage objectStorage;

    @MockBean
    private BillingClient billingClient;
    @MockBean
    private ExecuteMessageProducer messageProducer;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CallerContext seedAppKey(String quotaJson) {
        String appKeyId = "sk-exec-" + uniq();
        AppKey appKey = new AppKey();
        appKey.setAppKeyId(appKeyId);
        appKey.setTenantId("tenant-" + uniq());
        appKey.setSecretCipher("cipher");
        appKey.setQuota(quotaJson);
        appKey.setStatus(1);
        appKeyMapper.insert(appKey);
        return new CallerContext(appKeyId, appKey.getTenantId());
    }

    private void seedSkill(CallerContext caller, String skillCode, String outputConfig, String pricingConfig) {
        Skill skill = new Skill();
        skill.setTenantId(caller.tenantId());
        skill.setSkillCode(skillCode);
        skill.setName(skillCode);
        skill.setVisibility("PRIVATE");
        skill.setOutputConfig(outputConfig);
        skill.setPricingConfig(pricingConfig);
        skill.setDefaultVersion("1.0.0");
        skill.setStatus(1);
        skill.setCreatedBy(caller.appKeyId());
        skillMapper.insert(skill);
        SkillVersion version = new SkillVersion();
        version.setSkillId(skill.getId());
        version.setVersion("1.0.0");
        version.setOssKey("skill/%s/%s/1.0.0/skill.zip".formatted(caller.tenantId(), skillCode));
        version.setPackageSha256("a".repeat(64));
        version.setPackageSize(100L);
        version.setStatus(SkillVersion.STATUS_PUBLISHED);
        version.setUploadedBy(caller.appKeyId());
        skillVersionMapper.insert(version);
        // 受理第零关校验包存在：种子版本一并放置包对象
        objectStorage.put(version.getOssKey(), "zip-bytes".getBytes(), "application/zip");
    }

    private ExecuteRequest request(String skillCode) {
        return new ExecuteRequest(skillCode, null, null, null, null,
                List.of(new MaterialInput("text", null, "品牌要求")), "生成视频", null, null);
    }

    private void mockFreezeOk() {
        when(billingClient.freeze(any(), any(), anyLong()))
                .thenAnswer(inv -> new BillingViews.FreezeResult("hold_" + uniq(), inv.getArgument(2), false));
    }

    // ------------------------------------------------------------------
    // TC-EXE-001 正常受理
    // ------------------------------------------------------------------

    @Test
    void happy_path_creates_pending_execution_freezes_and_publishes() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode,
                "{\"countable\":true,\"defaultCount\":1,\"maxCount\":20}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        mockFreezeOk();

        ExecuteResult result = executeService.submit(caller, request(skillCode));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.taskId()).startsWith("task_");
        assertThat(result.resolvedVersion()).isEqualTo("1.0.0");
        assertThat(result.count()).isEqualTo(1);

        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, result.taskId()));
        assertThat(execution.getStatus()).isEqualTo("PENDING");
        assertThat(execution.getHoldId()).startsWith("hold_");
        assertThat(execution.getExpectedCount()).isEqualTo(1);
        assertThat(execution.getPricingSnapshot()).contains("PER_EXECUTION");
        assertThat(execution.getOutputConfigSnapshot()).contains("maxCount");
        assertThat(execution.getCallbackStatus()).isEqualTo("NO_CALLBACK");
        // 完整入参已落 OSS（worker input.json 数据源）
        assertThat(objectStorage.stat(execution.getInputRef())).isNotNull();
        // MQ 已投递
        verify(messageProducer).sendExecute(result.taskId());
        // 冻结金额 = 单价 × count
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(billingClient).freeze(eq(result.taskId()), eq(caller.appKeyId()), amount.capture());
        assertThat(amount.getValue()).isEqualTo(10L);
    }

    // ------------------------------------------------------------------
    // TC-EXE-003 余额不足：快速失败，任务不创建
    // ------------------------------------------------------------------

    @Test
    void insufficient_balance_fails_fast_without_execution() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode,
                "{\"countable\":true,\"defaultCount\":1,\"maxCount\":20}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        when(billingClient.freeze(any(), any(), anyLong()))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_BALANCE, "点数余额不足"));

        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
                    assertThat(((BizException) e).data())
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsKey("rechargeUrl");
                });

        assertThat(executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getAppKeyId, caller.appKeyId()))).isZero();
        verify(messageProducer, never()).sendExecute(any());
    }

    // ------------------------------------------------------------------
    // TC-EXE-004 count 规则
    // ------------------------------------------------------------------

    @Test
    void count_over_max_without_override_rejected_with_override_marked() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode,
                "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");

        ExecuteRequest over = new ExecuteRequest(skillCode, null, 6, false, null, null, "x", null, null);
        assertThatThrownBy(() -> executeService.submit(caller, over))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.COUNT_INVALID));

        mockFreezeOk();
        ExecuteRequest overWithOverride = new ExecuteRequest(skillCode, null, 6, true, null, null, "x", null, null);
        ExecuteResult result = executeService.submit(caller, overWithOverride);
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, result.taskId()));
        assertThat(execution.getCountOverridden()).isEqualTo(1);
        assertThat(execution.getExpectedCount()).isEqualTo(6);
        verify(billingClient).freeze(eq(result.taskId()), eq(caller.appKeyId()), eq(60L));
    }

    @Test
    void countable_false_rejects_count() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode, "{\"countable\":false}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");

        ExecuteRequest withCount = new ExecuteRequest(skillCode, null, 2, false, null, null, "x", null, null);
        assertThatThrownBy(() -> executeService.submit(caller, withCount))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.COUNT_INVALID));
    }

    // ------------------------------------------------------------------
    // TC-EXE-006 幂等命中：先于冻结
    // ------------------------------------------------------------------

    @Test
    void idempotent_hit_returns_existing_task_without_second_freeze() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode, "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        mockFreezeOk();

        ExecuteRequest first = new ExecuteRequest(skillCode, null, null, null, "req-" + uniq(),
                null, "x", null, null);
        ExecuteResult created = executeService.submit(caller, first);
        ExecuteResult again = executeService.submit(caller, first);

        assertThat(again.idempotentHit()).isTrue();
        assertThat(again.taskId()).isEqualTo(created.taskId());
        // 只冻结一次
        verify(billingClient, org.mockito.Mockito.times(1))
                .freeze(any(), eq(caller.appKeyId()), anyLong());
    }

    // ------------------------------------------------------------------
    // TC-EXE-007 并发同幂等键：败者立即释放 hold
    // ------------------------------------------------------------------

    @Test
    void duplicate_key_loser_releases_hold_and_returns_winner() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode, "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        String clientRequestId = "req-" + uniq();

        // 冻结回调中插入"并发胜者"execution（同幂等键），随后我方 insert 撞唯一键
        AtomicInteger freezes = new AtomicInteger();
        when(billingClient.freeze(any(), any(), anyLong())).thenAnswer(inv -> {
            if (freezes.incrementAndGet() == 1) {
                Execution winner = new Execution();
                winner.setTaskId("task_winner_" + uniq());
                winner.setClientRequestId(clientRequestId);
                winner.setAppKeyId(caller.appKeyId());
                winner.setTenantId(caller.tenantId());
                winner.setSkillCode(skillCode);
                winner.setSkillVersion("1.0.0");
                winner.setPackageSha256("a".repeat(64));
                winner.setExpectedCount(1);
                winner.setStatus("PENDING");
                winner.setCallbackStatus("NO_CALLBACK");
                winner.setModelCalls(0);
                winner.setTokensUsed(0);
                winner.setCreatedBy(caller.appKeyId());
                executionMapper.insert(winner);
            }
            return new BillingViews.FreezeResult("hold_" + uniq(), 10, false);
        });

        ExecuteRequest racing = new ExecuteRequest(skillCode, null, null, null, clientRequestId,
                null, "x", null, null);
        ExecuteResult result = executeService.submit(caller, racing);

        assertThat(result.idempotentHit()).isTrue();
        assertThat(result.taskId()).startsWith("task_winner_");
        // 败者 hold 立即释放（不等对账）
        verify(billingClient).release(org.mockito.ArgumentMatchers.startsWith("hold_"));
        // 败者不投递 MQ
        verify(messageProducer, never()).sendExecute(any());
    }

    // ------------------------------------------------------------------
    // TC-EXE-008 素材跨租户 / 未确认
    // ------------------------------------------------------------------

    @Test
    void cross_tenant_or_unconfirmed_material_rejected() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode, "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");

        // 他租户素材
        Material foreign = seedMaterial("other-tenant", Material.STATUS_ACTIVE, "video", "mp4");
        // 本租户未确认素材
        Material unconfirmed = seedMaterial(caller.tenantId(), Material.STATUS_PENDING_UPLOAD, "video", "mp4");

        ExecuteRequest foreignReq = new ExecuteRequest(skillCode, null, null, null, null,
                List.of(new MaterialInput("video", "oss://" + foreign.getOssKey(), null)), "x", null, null);
        assertThatThrownBy(() -> executeService.submit(caller, foreignReq))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        ExecuteRequest unconfirmedReq = new ExecuteRequest(skillCode, null, null, null, null,
                List.of(new MaterialInput("video", "oss://" + unconfirmed.getOssKey(), null)), "x", null, null);
        assertThatThrownBy(() -> executeService.submit(caller, unconfirmedReq))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private Material seedMaterial(String tenantId, int status, String type, String ext) {
        Material material = new Material();
        material.setMaterialId("mat_" + uniq());
        material.setTenantId(tenantId);
        material.setMaterialType(type);
        material.setOssKey(tenantId + "/materials/" + material.getMaterialId() + "." + ext);
        material.setUploadChannel("multipart");
        material.setStatus(status);
        material.setFilename("f." + ext);
        material.setFileSize(100L);
        materialMapper.insert(material);
        return material;
    }

    // ------------------------------------------------------------------
    // TC-EXE-009 mq.send 失败：当场回滚
    // ------------------------------------------------------------------

    @Test
    void mq_failure_rolls_back_execution_and_releases_hold() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "video-gen-" + uniq();
        seedSkill(caller, skillCode, "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        mockFreezeOk();
        doThrow(new IllegalStateException("rocketmq down"))
                .when(messageProducer).sendExecute(any());

        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        assertThat(executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getAppKeyId, caller.appKeyId()))).isZero();
        verify(billingClient).release(org.mockito.ArgumentMatchers.startsWith("hold_"));
    }

    // ------------------------------------------------------------------
    // FREE 受理 / METERED 校验 / Skill 不存在
    // ------------------------------------------------------------------

    @Test
    void free_skill_executes_without_freeze_or_hold() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "free-skill-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"FREE\"}");

        ExecuteResult result = executeService.submit(caller, request(skillCode));

        assertThat(result.status()).isEqualTo("PENDING");
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, result.taskId()));
        assertThat(execution.getPricingSnapshot()).contains("FREE");
        assertThat(execution.getHoldId()).isNull();
        // FREE 0 点受理：不触 billing，任务照常进 MQ
        verify(billingClient, never()).freeze(any(), any(), anyLong());
        verify(messageProducer).sendExecute(result.taskId());
    }

    @Test
    void metered_without_cap_points_rejected() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "metered-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"METERED\"}");

        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.BILLING_VALIDATION_FAILED));
    }

    @Test
    void metered_freezes_cap_points() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "metered-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"METERED\",\"capPoints\":500}");
        mockFreezeOk();

        executeService.submit(caller, request(skillCode));

        verify(billingClient).freeze(any(), eq(caller.appKeyId()), eq(500L));
    }

    @Test
    void unknown_skill_or_unpublished_version_rejected() {
        CallerContext caller = seedAppKey(null);
        assertThatThrownBy(() -> executeService.submit(caller, request("ghost-" + uniq())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.SKILL_NOT_FOUND));

        // 版本未发布
        String skillCode = "unpublished-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");
        Skill seeded = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        SkillVersion version = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, seeded.getId())
                .eq(SkillVersion::getVersion, "1.0.0").last("LIMIT 1"));
        version.setStatus(SkillVersion.STATUS_UPLOADED);
        skillVersionMapper.updateById(version);
        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.SKILL_NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // TC-EXE-010/011/012 限流与配额
    // ------------------------------------------------------------------

    @Test
    void qps_limit_rejects_burst() {
        CallerContext caller = seedAppKey("{\"qps\":1,\"maxRunning\":50,\"dailyLimit\":10000}");
        String skillCode = "qps-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");
        mockFreezeOk();

        executeService.submit(caller, request(skillCode)); // 第 1 次（1s 窗口内）
        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void tenant_running_limit_rejects_over_concurrency() {
        CallerContext caller = seedAppKey("{\"qps\":100,\"maxRunning\":1,\"dailyLimit\":10000}");
        String skillCode = "run-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");
        mockFreezeOk();

        executeService.submit(caller, request(skillCode)); // 占满 1 个并发
        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void daily_limit_rejects_after_quota() {
        CallerContext caller = seedAppKey("{\"qps\":100,\"maxRunning\":50,\"dailyLimit\":1}");
        String skillCode = "daily-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");
        mockFreezeOk();

        executeService.submit(caller, request(skillCode));
        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    // ------------------------------------------------------------------
    // context 上限与 callback 校验
    // ------------------------------------------------------------------

    @Test
    void context_over_4kb_rejected() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "ctx-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode big = mapper.createObjectNode();
        big.put("blob", "x".repeat(5 * 1024));
        ExecuteRequest request = new ExecuteRequest(skillCode, null, null, null, null, null, "x", big, null);
        assertThatThrownBy(() -> executeService.submit(caller, request))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void callback_url_to_reserved_host_rejected() {
        CallerContext caller = seedAppKey(null);
        String skillCode = "cb-" + uniq();
        seedSkill(caller, skillCode, "{}", "{\"mode\":\"PER_EXECUTION\",\"points\":5}");

        ExecuteRequest request = new ExecuteRequest(skillCode, null, null, null, null, null, "x", null,
                "http://localhost:9000/cb");
        assertThatThrownBy(() -> executeService.submit(caller, request))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
