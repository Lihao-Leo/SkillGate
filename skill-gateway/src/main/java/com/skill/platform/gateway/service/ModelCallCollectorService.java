package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.ModelCall;
import com.skill.platform.gateway.dal.entity.ModelProvider;
import com.skill.platform.gateway.dal.mapper.ModelCallMapper;
import com.skill.platform.gateway.dal.mapper.ModelProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型调用明细 collector（V2.1）：model_call 单点写库通道，语义对齐
 * {@link ExecutionEventCollectorService}——观测数据，DB 故障丢弃不阻塞主链路。
 *
 * <p>落库前按 alias 查 model_provider 费率折算 cost_points
 * （prompt/1k × 输入单价 + completion/1k × 输出单价；费率 30s 缓存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCallCollectorService {

    /** 单批上限（internal API 整批校验，超限 40001） */
    public static final int MAX_BATCH = 200;

    private static final long RATE_TTL_MILLIS = 30_000L;

    private final ModelCallMapper modelCallMapper;
    private final ModelProviderMapper modelProviderMapper;

    /** 费率快照：null 分量 = 未配置该方向单价。 */
    private record TokenRate(BigDecimal inputPer1k, BigDecimal outputPer1k) {
    }

    private record CachedRate(TokenRate rate, long loadedAt) {
    }

    private final Map<String, CachedRate> rateCache = new ConcurrentHashMap<>();

    /**
     * 上报输入：taskId 必填；tokens/耗时等缺失按可空落库。
     */
    public record ModelCallInput(String taskId, String tenantId, String skillCode, Integer seq,
                                 String kind, String alias, String modelName, String provider,
                                 Integer promptTokens, Integer completionTokens, Long durationMs,
                                 String status, String errorCode, LocalDateTime occurredAt) {
    }

    /**
     * 批量落库模型调用明细。
     *
     * @return 实际落库条数；DB 故障返回 0
     * @throws BizException 空批/超 {@link #MAX_BATCH}/缺 taskId（整批拒绝语义）
     */
    public int collectBatch(List<ModelCallInput> calls) {
        if (calls == null || calls.isEmpty()) {
            return 0;
        }
        if (calls.size() > MAX_BATCH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单批明细数超上限 " + MAX_BATCH);
        }
        for (ModelCallInput call : calls) {
            if (call.taskId() == null || call.taskId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "taskId 不能为空");
            }
        }
        try {
            int inserted = 0;
            for (ModelCallInput call : calls) {
                modelCallMapper.insert(toEntity(call));
                inserted++;
            }
            return inserted;
        } catch (Exception dbError) {
            log.warn("model_call 落库失败，本批 {} 条明细丢弃（观测不阻塞主链路）: {}",
                    calls.size(), dbError.getMessage());
            return 0;
        }
    }

    /** 任务维度明细查询（admin 执行抽屉，limit ≤ 500）。 */
    public List<ModelCall> listByTask(String taskId, int limit) {
        return modelCallMapper.selectList(new LambdaQueryWrapper<ModelCall>()
                .eq(ModelCall::getTaskId, taskId)
                .orderByAsc(ModelCall::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500)));
    }

    private ModelCall toEntity(ModelCallInput call) {
        ModelCall entity = new ModelCall();
        entity.setTaskId(call.taskId());
        entity.setTenantId(call.tenantId());
        entity.setSkillCode(call.skillCode());
        entity.setSeq(call.seq());
        entity.setKind(call.kind() == null || call.kind().isBlank() ? "chat" : call.kind());
        entity.setAlias(call.alias());
        entity.setModelName(call.modelName());
        entity.setProvider(call.provider());
        entity.setPromptTokens(call.promptTokens());
        entity.setCompletionTokens(call.completionTokens());
        entity.setTotalTokens(totalOf(call.promptTokens(), call.completionTokens()));
        entity.setCostPoints(costOf(call));
        entity.setDurationMs(call.durationMs());
        entity.setStatus(call.status() == null || call.status().isBlank() ? "OK" : call.status());
        entity.setErrorCode(call.errorCode());
        entity.setCreatedAt(call.occurredAt() == null ? LocalDateTime.now() : call.occurredAt());
        return entity;
    }

    private static Integer totalOf(Integer prompt, Integer completion) {
        if (prompt == null && completion == null) {
            return null;
        }
        return (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
    }

    /**
     * 费率折算（点数）：prompt/1k × 输入单价 + completion/1k × 输出单价，4 位小数；
     * alias 未配置费率时返回 null（明细仍落库，仅无成本口径）。
     */
    private BigDecimal costOf(ModelCallInput call) {
        if (call.alias() == null || call.alias().isBlank()
                || (call.promptTokens() == null && call.completionTokens() == null)) {
            return null;
        }
        TokenRate rate = ratesOf(call.alias());
        if (rate == null || (rate.inputPer1k() == null && rate.outputPer1k() == null)) {
            return null;
        }
        BigDecimal input = BigDecimal.valueOf(call.promptTokens() == null ? 0 : call.promptTokens());
        BigDecimal output = BigDecimal.valueOf(call.completionTokens() == null ? 0 : call.completionTokens());
        BigDecimal cost = BigDecimal.ZERO;
        if (rate.inputPer1k() != null) {
            cost = cost.add(rate.inputPer1k().multiply(input));
        }
        if (rate.outputPer1k() != null) {
            cost = cost.add(rate.outputPer1k().multiply(output));
        }
        return cost.divide(new BigDecimal(1000), 4, RoundingMode.HALF_UP);
    }

    /** 费率查询（30s 缓存，避免每次调用明细落库都查 provider 表）。 */
    private TokenRate ratesOf(String alias) {
        CachedRate cached = rateCache.get(alias);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < RATE_TTL_MILLIS) {
            return cached.rate();
        }
        ModelProvider provider = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        TokenRate rate = provider == null
                ? new TokenRate(null, null)
                : new TokenRate(provider.getCostPer1kInputTokens(), provider.getCostPer1kOutputTokens());
        rateCache.put(alias, new CachedRate(rate, System.currentTimeMillis()));
        return rate;
    }
}
