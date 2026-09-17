package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.common.Ids;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行受理（技术方案 v6.5 §4.2 五关，顺序不变量）：
 * ① HMAC 鉴权（拦截器）→ ② 限流/配额 → ③ 计费预校验+冻结 → ④ 调度（MQ）→ ⑤ 返回 taskId。
 *
 * <p>故障路径不变量（§4.10）：幂等命中先于冻结；并发同幂等键败者立即释放 hold；
 * mq.send 可感知失败当场回滚（删 execution + 释放 hold + 50001）不留悬空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecuteService {

    /** 走素材上传通道的语义类型（§4.3）；text 内联、link 外部 URL 不落平台存储 */
    private static final Set<String> OSS_TYPES = Set.of("video", "image", "audio", "document", "data", "other");
    private static final int CONTEXT_MAX_BYTES = 4 * 1024;
    private static final int INSTRUCTIONS_MAX_BYTES = 32 * 1024;

    private final AppKeyMapper appKeyMapper;
    private final SkillMapper skillMapper;
    private final SkillVersionMapper skillVersionMapper;
    private final ExecutionMapper executionMapper;
    private final MaterialMapper materialMapper;
    private final BillingClient billingClient;
    private final ExecuteMessageProducer messageProducer;
    private final ObjectStorage objectStorage;
    private final RateLimitService rateLimitService;
    private final CallbackUrlValidator callbackUrlValidator;

    @Value("${skill-platform.recharge-url:https://recharge.example.com}")
    private String rechargeUrl;

    /** 受理时锁定的 Skill 解析结果（版本 + 运营配置快照数据源） */
    private record ResolvedSkill(Skill skill, SkillVersion version,
                                 JsonNode outputConfig, JsonNode pricing) {
    }

    public ExecuteResult submit(CallerContext caller, ExecuteRequest request) {
        // ---- ② 限流/配额（QPS / 日调用量；并发占位在动账前获取）----
        AppKey appKey = appKeyMapper.selectOne(new LambdaQueryWrapper<AppKey>()
                .eq(AppKey::getAppKeyId, caller.appKeyId()));
        if (appKey == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "AppKey 无效");
        }
        RateLimitService.Quota quota = rateLimitService.quotaOf(appKey);
        rateLimitService.checkQps(caller.appKeyId(), quota);
        rateLimitService.checkDailyQuota(caller.appKeyId(), quota);

        // ---- 校验：Skill / 版本 / 定价 / 数量 / 素材 / 回调 ----
        ResolvedSkill resolved = resolveSkill(caller, request);
        validatePricing(resolved.pricing());
        CountResolver.Resolution count = CountResolver.resolve(
                request.count(),
                resolved.outputConfig().path("countable").asBoolean(false),
                resolved.outputConfig().path("defaultCount").canConvertToInt()
                        ? resolved.outputConfig().path("defaultCount").asInt() : null,
                resolved.outputConfig().path("maxCount").canConvertToInt()
                        ? resolved.outputConfig().path("maxCount").asInt() : null,
                Boolean.TRUE.equals(request.override()));
        validateMaterials(caller, request.materials());
        if (request.callbackUrl() != null && !request.callbackUrl().isBlank()) {
            callbackUrlValidator.validate(request.callbackUrl(), callbackDomainList(appKey));
        }
        String contextJson = validateContext(request.context());
        if (request.instructions() != null
                && request.instructions().getBytes(StandardCharsets.UTF_8).length > INSTRUCTIONS_MAX_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID, "instructions 超过 32KB 上限");
        }

        // ---- 幂等命中【先于冻结】（TC-EXE-006）----
        if (request.clientRequestId() != null && !request.clientRequestId().isBlank()) {
            Execution hit = findByIdempotentKey(caller.appKeyId(), request.clientRequestId());
            if (hit != null) {
                return new ExecuteResult(hit.getTaskId(), hit.getSkillVersion(),
                        hit.getExpectedCount(), hit.getStatus(), true);
            }
        }

        rateLimitService.tryAcquireRunning(caller.tenantId(), quota);
        try {
            return accept(caller, request, resolved, count, contextJson);
        } catch (Exception e) {
            rateLimitService.releaseRunning(caller.tenantId());
            throw e;
        }
    }

    private ExecuteResult accept(CallerContext caller, ExecuteRequest request, ResolvedSkill resolved,
                                 CountResolver.Resolution count, String contextJson) {
        String taskId = Ids.next("task_");
        long amount = freezeAmount(resolved.pricing(), count.count());

        // 完整入参 JSON 落 OSS（worker 经 input_ref 读取并注入沙箱 input.json，§4.7）
        String inputRef = caller.tenantId() + "/inputs/" + taskId + ".json";
        objectStorage.put(inputRef, buildInputJson(request, count.count())
                .getBytes(StandardCharsets.UTF_8), "application/json");

        // ③ 冻结（余额不足快速失败 40201，任务不创建；响应带 rechargeUrl）
        String holdId = null;
        if (amount > 0) {
            try {
                BillingViews.FreezeResult freeze = billingClient.freeze(taskId, caller.appKeyId(), amount);
                if (freeze == null || freeze.holdId() == null) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "冻结响应异常");
                }
                holdId = freeze.holdId();
            } catch (BizException e) {
                if (e.errorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                    throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, e.getMessage(),
                            Map.of("rechargeUrl", rechargeUrl));
                }
                throw e;
            }
        }

        // ④ 落库（uk_key_client_req 并发幂等冲突 → 败者立即释放 hold，返回胜者 taskId）
        Execution execution = buildExecution(caller, request, resolved, count, taskId, holdId, inputRef,
                contextJson);
        try {
            executionMapper.insert(execution);
        } catch (DuplicateKeyException e) {
            log.warn("concurrent duplicate clientRequestId, loser releases hold: taskId={}, key={}",
                    taskId, request.clientRequestId());
            releaseQuietly(holdId);
            rateLimitService.releaseRunning(caller.tenantId());
            Execution winner = findByIdempotentKey(caller.appKeyId(), request.clientRequestId());
            if (winner == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "幂等冲突且无法读取原任务，请重试");
            }
            return new ExecuteResult(winner.getTaskId(), winner.getSkillVersion(),
                    winner.getExpectedCount(), winner.getStatus(), true);
        }

        try {
            messageProducer.sendExecute(taskId);
        } catch (Exception e) {
            // 可感知失败：当场回滚（删 execution + 释放 hold）不留悬空（TC-EXE-009）
            log.error("mq send failed, rolling back accept: taskId={}", taskId, e);
            executionMapper.delete(new LambdaQueryWrapper<Execution>()
                    .eq(Execution::getTaskId, taskId)
                    .eq(Execution::getStatus, Execution.STATUS_PENDING));
            releaseQuietly(holdId);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "任务调度失败，请重试");
        }
        // ⑤ 返回 taskId（终态经回调或轮询获取）
        return new ExecuteResult(execution.getTaskId(), execution.getSkillVersion(),
                execution.getExpectedCount(), execution.getStatus(), false);
    }

    private Execution buildExecution(CallerContext caller, ExecuteRequest request, ResolvedSkill resolved,
                                     CountResolver.Resolution count, String taskId, String holdId,
                                     String inputRef, String contextJson) {
        Execution execution = new Execution();
        execution.setTaskId(taskId);
        execution.setClientRequestId(request.clientRequestId());
        execution.setAppKeyId(caller.appKeyId());
        execution.setTenantId(caller.tenantId());
        execution.setSkillCode(resolved.skill().getSkillCode());
        execution.setSkillVersion(resolved.version().getVersion());
        execution.setPackageSha256(resolved.version().getPackageSha256());
        execution.setInputRef(inputRef);
        execution.setContext(contextJson);
        execution.setExpectedCount(count.count());
        execution.setCountOverridden(count.overridden() ? 1 : 0);
        execution.setOutputConfigSnapshot(resolved.outputConfig().toString());
        execution.setPricingSnapshot(resolved.pricing().toString());
        execution.setHoldId(holdId);
        execution.setStatus(Execution.STATUS_PENDING);
        execution.setCallbackUrl(request.callbackUrl());
        execution.setCallbackStatus(request.callbackUrl() == null || request.callbackUrl().isBlank()
                ? "NO_CALLBACK" : "PENDING");
        execution.setModelCalls(0);
        execution.setTokensUsed(0);
        execution.setCreatedBy(caller.appKeyId());
        return execution;
    }

    private ResolvedSkill resolveSkill(CallerContext caller, ExecuteRequest request) {
        // 本租户 Skill 优先；未命中找市场公开 Skill（§3.6：查询经 AppKey 推导租户条件）
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, request.skillCode())
                .eq(Skill::getTenantId, caller.tenantId())
                .last("LIMIT 1"));
        if (skill == null) {
            skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getSkillCode, request.skillCode())
                    .eq(Skill::getVisibility, Skill.VISIBILITY_PUBLIC)
                    .eq(Skill::getStatus, 1)
                    .last("LIMIT 1"));
        }
        if (skill == null || skill.getStatus() != 1) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "Skill 不存在: " + request.skillCode());
        }

        String version = request.version() == null || request.version().isBlank()
                ? skill.getDefaultVersion() : request.version();
        if (version == null || version.isBlank()) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND,
                    "Skill 未设置默认版本: " + request.skillCode());
        }
        SkillVersion skillVersion = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId())
                .eq(SkillVersion::getVersion, version)
                .last("LIMIT 1"));
        if (skillVersion == null || skillVersion.getStatus() != SkillVersion.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND,
                    "Skill 版本不可用: " + request.skillCode() + "@" + version);
        }
        // 受理第零关：包文件必须在对象存储中（防存储清理后任务进 MQ 才失败，§4.2 前置校验）
        if (objectStorage.stat(skillVersion.getOssKey()) == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND,
                    "Skill 包缺失（存储对象不存在: " + skillVersion.getOssKey()
                            + "），请重新上传该版本");
        }
        return new ResolvedSkill(skill, skillVersion,
                JsonCodec.treeOrEmpty(skill.getOutputConfig()),
                JsonCodec.treeOrEmpty(skill.getPricingConfig()));
    }

    private void validatePricing(JsonNode pricing) {
        String mode = pricing.path("mode").asText("PER_EXECUTION");
        if (Skill.PRICING_FREE.equals(mode)) {
            // FREE 可执行：0 点受理，不冻结不结算（hold_id=NULL，worker 侧 actual_points 恒 0）；
            // package 端点仍是其离线分发通道，两者不互斥
            return;
        }
        if (Skill.PRICING_METERED.equals(mode)) {
            if (pricing.path("capPoints").asLong(0) <= 0) {
                throw new BizException(ErrorCode.BILLING_VALIDATION_FAILED,
                        "METERED 定价未配置 capPoints，不可执行");
            }
        } else if (pricing.path("points").asLong(0) <= 0) {
            throw new BizException(ErrorCode.BILLING_VALIDATION_FAILED,
                    "PER_EXECUTION 定价未配置有效 points，不可执行");
        }
    }

    private long freezeAmount(JsonNode pricing, int count) {
        if (Skill.PRICING_METERED.equals(pricing.path("mode").asText("PER_EXECUTION"))) {
            return pricing.path("capPoints").asLong();
        }
        return pricing.path("points").asLong() * count;
    }

    /**
     * 失败任务重试（§10.1 任务列表）：按原任务入参重放一次完整受理
     * （新 taskId + 新冻结），原失败记录保留审计。
     */
    public ExecuteResult retry(CallerContext caller, String taskId) {
        Execution execution = executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getTaskId, taskId)
                .eq(Execution::getTenantId, caller.tenantId())
                .last("LIMIT 1"));
        if (execution == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "任务不存在: " + taskId);
        }
        if (!Execution.STATUS_FAILED.equals(execution.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅失败任务可重试: " + taskId);
        }
        if (execution.getInputRef() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原任务无入参存档，不可重试");
        }
        JsonNode input = JsonCodec.treeOrEmpty(
                new String(objectStorage.get(execution.getInputRef()), StandardCharsets.UTF_8));
        List<ExecuteDtos.MaterialInput> materials = new ArrayList<>();
        for (JsonNode m : input.path("materials")) {
            materials.add(new ExecuteDtos.MaterialInput(
                    m.path("type").asText(null), m.path("url").asText(null), m.path("content").asText(null)));
        }
        ExecuteDtos.ExecuteRequest request = new ExecuteDtos.ExecuteRequest(
                input.path("skillCode").asText(execution.getSkillCode()),
                execution.getSkillVersion(),
                null, null,
                "retry-" + taskId + "-" + System.currentTimeMillis(),
                materials.isEmpty() ? null : materials,
                input.path("instructions").asText(null).isBlank() ? null : input.path("instructions").asText(null),
                input.path("context").isEmpty() ? null : input.path("context"),
                null);
        return submit(caller, request);
    }

    /** 素材归属校验（§7：oss:// 素材归属本 AppKey 租户，跨租户/未确认 → 40301，TC-EXE-008） */
    private void validateMaterials(CallerContext caller, List<MaterialInput> materials) {
        if (materials == null) {
            return;
        }
        List<Long> referencedIds = new ArrayList<>();
        for (MaterialInput material : materials) {
            String type = material.type();
            if (type == null || type.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "materials[].type 不能为空");
            }
            if ("text".equals(type)) {
                if (material.content() == null || material.content().isBlank()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "text 素材缺少 content");
                }
                continue;
            }
            if ("link".equals(type)) {
                if (material.url() == null || !material.url().startsWith("http")) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "link 素材缺少合法 url");
                }
                continue;
            }
            if (!OSS_TYPES.contains(type)) {
                throw new BizException(ErrorCode.MATERIAL_TYPE_UNSUPPORTED, "素材类型不支持: " + type);
            }
            if (material.url() == null || !material.url().startsWith("oss://")) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        type + " 素材必须为 oss:// 引用（经素材上传接口获取）");
            }
            String ossKey = material.url().substring("oss://".length());
            Material row = materialMapper.selectOne(new LambdaQueryWrapper<Material>()
                    .eq(Material::getOssKey, ossKey)
                    .last("LIMIT 1"));
            if (row == null || !row.getTenantId().equals(caller.tenantId())
                    || row.getStatus() != Material.STATUS_ACTIVE) {
                throw new BizException(ErrorCode.FORBIDDEN, "素材不存在、未确认或跨租户引用: " + ossKey);
            }
            referencedIds.add(row.getId());
        }
        if (!referencedIds.isEmpty()) {
            // 回写最近引用时间（7 天未引用清理依据，§6.5）
            materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                    .in(Material::getId, referencedIds)
                    .set(Material::getLastReferencedAt, LocalDateTime.now()));
        }
    }

    private String validateContext(JsonNode context) {
        if (context == null || context.isNull()) {
            return null;
        }
        String json = context.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > CONTEXT_MAX_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID, "context 超过 4KB 上限");
        }
        return json;
    }

    /** §4.7 input.json 契约：materials + instructions + context + count 完整入参 */
    private String buildInputJson(ExecuteRequest request, int count) {
        return JsonCodec.write(Map.of(
                "skillCode", request.skillCode() == null ? "" : request.skillCode(),
                "version", request.version() == null ? "" : request.version(),
                "count", count,
                "materials", request.materials() == null ? List.of() : request.materials(),
                "instructions", request.instructions() == null ? "" : request.instructions(),
                "context", request.context() == null ? Map.of() : request.context()));
    }

    private Execution findByIdempotentKey(String appKeyId, String clientRequestId) {
        return executionMapper.selectOne(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getAppKeyId, appKeyId)
                .eq(Execution::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
    }

    private List<String> callbackDomainList(AppKey appKey) {
        if (appKey.getCallbackDomains() == null || appKey.getCallbackDomains().isBlank()) {
            return List.of();
        }
        try {
            return JsonCodec.read(appKey.getCallbackDomains(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("parse callback domains failed: {}", appKey.getAppKeyId());
            return List.of();
        }
    }

    private void releaseQuietly(String holdId) {
        if (holdId == null) {
            return;
        }
        try {
            billingClient.release(holdId);
        } catch (Exception releaseFailure) {
            // 释放失败留待 billing 对账兜底（FROZEN>24h 自动释放），不阻塞回滚主流程
            log.error("release hold failed, reconcile will cover: holdId={}", holdId, releaseFailure);
        }
    }
}
