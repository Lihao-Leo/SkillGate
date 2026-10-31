package com.skill.platform.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.dal.entity.ExecutionEvent;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillVersion;
import com.skill.platform.gateway.dal.mapper.AppKeyMapper;
import com.skill.platform.gateway.dal.mapper.ExecutionEventMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 受理五关事件链测试（TC-TR-001 / TC-TR-007 受理侧）：成功受理产出
 * ACCEPT→AUTH→RATE_LIMIT→FREEZE→ENQUEUE 事件且 seq 递增；余额不足时 FREEZE FAILED。
 */
@SpringBootTest
@ActiveProfiles("local")
class ExecuteServiceEventsTest {

    @Autowired
    private ExecuteService executeService;
    @Autowired
    private AppKeyMapper appKeyMapper;
    @Autowired
    private SkillMapper skillMapper;
    @Autowired
    private SkillVersionMapper skillVersionMapper;
    @Autowired
    private ExecutionEventMapper executionEventMapper;
    @Autowired
    private ObjectStorage objectStorage;

    @MockBean
    private BillingClient billingClient;
    @MockBean
    private ExecuteMessageProducer messageProducer;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CallerContext seedAppKey() {
        String appKeyId = "sk-ev-" + uniq();
        AppKey appKey = new AppKey();
        appKey.setAppKeyId(appKeyId);
        appKey.setTenantId("tenant-" + uniq());
        appKey.setSecretCipher("cipher");
        appKey.setStatus(1);
        appKeyMapper.insert(appKey);
        return new CallerContext(appKeyId, appKey.getTenantId());
    }

    private void seedSkill(CallerContext caller, String skillCode) {
        Skill skill = new Skill();
        skill.setTenantId(caller.tenantId());
        skill.setSkillCode(skillCode);
        skill.setName(skillCode);
        skill.setVisibility("PRIVATE");
        skill.setOutputConfig("{\"countable\":true,\"defaultCount\":1,\"maxCount\":20}");
        skill.setPricingConfig("{\"mode\":\"PER_EXECUTION\",\"points\":10}");
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
        objectStorage.put(version.getOssKey(), "zip-bytes".getBytes(), "application/zip");
    }

    private ExecuteRequest request(String skillCode) {
        return new ExecuteRequest(skillCode, null, null, null, null,
                List.of(new MaterialInput("text", null, "品牌要求")), "生成视频", null, null);
    }

    private List<ExecutionEvent> eventsOf(String taskId) {
        return executionEventMapper.selectList(new LambdaQueryWrapper<ExecutionEvent>()
                .eq(ExecutionEvent::getTaskId, taskId)
                .orderByAsc(ExecutionEvent::getSeq));
    }

    @Test
    void happy_path_records_five_accept_stage_events_with_continuous_seq() {
        CallerContext caller = seedAppKey();
        String skillCode = "ev-gen-" + uniq();
        seedSkill(caller, skillCode);
        when(billingClient.freeze(any(), any(), anyLong()))
                .thenAnswer(inv -> new BillingViews.FreezeResult("hold_" + uniq(), inv.getArgument(2), false));

        ExecuteResult result = executeService.submit(caller, request(skillCode));

        List<ExecutionEvent> events = eventsOf(result.taskId());
        assertThat(events).extracting(ExecutionEvent::getStage)
                .containsExactly("ACCEPT", "AUTH", "RATE_LIMIT", "FREEZE", "ENQUEUE");
        assertThat(events).extracting(ExecutionEvent::getSeq).containsExactly(1, 2, 3, 4, 5);
        assertThat(events).extracting(ExecutionEvent::getStatus)
                .containsOnly("OK");
        assertThat(events).extracting(ExecutionEvent::getService).containsOnly("gateway");
        // FREEZE 事件带 holdId/points
        ExecutionEvent freeze = events.get(3);
        assertThat(freeze.getDetail()).contains("hold_").contains("10");
    }

    @Test
    void insufficient_balance_records_freeze_failed() {
        CallerContext caller = seedAppKey();
        String skillCode = "ev-gen-" + uniq();
        seedSkill(caller, skillCode);
        when(billingClient.freeze(any(), any(), anyLong())).thenThrow(
                new BizException(com.skill.platform.gateway.common.ErrorCode.INSUFFICIENT_BALANCE,
                        "余额不足"));

        String skillCodeFinal = skillCode;
        assertThatThrownBy(() -> executeService.submit(caller, request(skillCodeFinal)))
                .isInstanceOf(BizException.class);

        String taskId = executionEventMapper.selectList(new LambdaQueryWrapper<ExecutionEvent>()
                        .eq(ExecutionEvent::getStage, "FREEZE")
                        .eq(ExecutionEvent::getStatus, "FAILED")
                        .orderByDesc(ExecutionEvent::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElseThrow().getTaskId();
        List<ExecutionEvent> events = eventsOf(taskId);
        Map<String, List<ExecutionEvent>> byStage = events.stream()
                .collect(Collectors.groupingBy(ExecutionEvent::getStage));
        assertThat(byStage).containsKeys("ACCEPT", "AUTH", "RATE_LIMIT", "FREEZE");
        assertThat(byStage.get("FREEZE")).extracting(ExecutionEvent::getStatus)
                .containsOnly("FAILED");
        assertThat(events.stream().filter(e -> "ENQUEUE".equals(e.getStage())).findAny())
                .isEmpty();
    }

    @Test
    void invalid_app_key_records_auth_failed() {
        String ghostTenant = "tenant-" + uniq();
        CallerContext caller = new CallerContext("sk-ghost-" + uniq(), ghostTenant);
        String skillCode = "ev-gen-" + uniq();
        seedSkill(caller, skillCode);

        assertThatThrownBy(() -> executeService.submit(caller, request(skillCode)))
                .isInstanceOf(BizException.class);

        List<ExecutionEvent> authFailed = executionEventMapper.selectList(
                new LambdaQueryWrapper<ExecutionEvent>()
                        .eq(ExecutionEvent::getStage, "AUTH")
                        .eq(ExecutionEvent::getStatus, "FAILED")
                        .orderByDesc(ExecutionEvent::getId)
                        .last("LIMIT 1"));
        assertThat(authFailed).isNotEmpty();
        assertThat(authFailed.get(0).getTenantId()).isEqualTo(ghostTenant);
    }
}
