package com.skill.platform.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.infra.KvStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis 三级限流与配额（§3.1 第②关 / §7）：
 * <ul>
 *   <li>AppKey QPS：1s 固定窗口（默认 10）</li>
 *   <li>AppKey 日调用量（默认 1 万）</li>
 *   <li>租户并发 RUNNING：租户级聚合计数（默认 50，防多 key 拆分绕过）——
 *       受理成功时获取，终态由漂移修复任务按 DB 真值重置</li>
 * </ul>
 * 配额来源 app_key.quota JSON，未配置用全局默认。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quota(Integer qps, Integer maxRunning, Integer dailyLimit) {
    }

    private final KvStore kv;
    private final Clock clock;
    private final SysConfigService sysConfigService;

    @Value("${skill-platform.quota.qps:10}")
    private int defaultQps;
    @Value("${skill-platform.quota.max-running:50}")
    private int defaultMaxRunning;
    @Value("${skill-platform.quota.daily-limit:10000}")
    private int defaultDailyLimit;

    public Quota quotaOf(AppKey appKey) {
        String json = appKey.getQuota();
        if (json == null || json.isBlank()) {
            return new Quota(sysConfigService.intOf("quota.qps", defaultQps),
                    sysConfigService.intOf("quota.max-running", defaultMaxRunning),
                    sysConfigService.intOf("quota.daily-limit", defaultDailyLimit));
        }
        try {
            Quota quota = JsonCodec.read(json, Quota.class);
            return new Quota(
                    quota.qps() == null ? sysConfigService.intOf("quota.qps", defaultQps) : quota.qps(),
                    quota.maxRunning() == null ? sysConfigService.intOf("quota.max-running", defaultMaxRunning) : quota.maxRunning(),
                    quota.dailyLimit() == null ? sysConfigService.intOf("quota.daily-limit", defaultDailyLimit) : quota.dailyLimit());
        } catch (Exception e) {
            log.warn("parse quota failed, fallback to defaults: appKey={}", appKey.getAppKeyId());
            return new Quota(sysConfigService.intOf("quota.qps", defaultQps),
                    sysConfigService.intOf("quota.max-running", defaultMaxRunning),
                    sysConfigService.intOf("quota.daily-limit", defaultDailyLimit));
        }
    }

    /** AppKey QPS：1 秒固定窗口 */
    public void checkQps(String appKeyId, Quota quota) {
        String key = "rate:qps:" + appKeyId + ":" + (clock.millis() / 1000);
        long hits = kv.incr(key, Duration.ofSeconds(2));
        if (hits > quota.qps()) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "QPS 超限（上限 " + quota.qps() + "/s）");
        }
    }

    /** 日调用量：自然日窗口，次日自动恢复 */
    public void checkDailyQuota(String appKeyId, Quota quota) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "quota:day:" + appKeyId + ":" + day;
        long count = kv.incr(key, Duration.ofHours(25));
        if (count > quota.dailyLimit()) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "日调用量超限（上限 " + quota.dailyLimit() + "）");
        }
    }

    /** 租户并发占位：受理成功即在途；超限立即释放并 42901 */
    public void tryAcquireRunning(String tenantId, Quota quota) {
        String key = "tenant:running:" + tenantId;
        long running = kv.incr(key, Duration.ofHours(26));
        if (running > quota.maxRunning()) {
            kv.decrFloorZero(key);
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "租户并发执行超限（上限 " + quota.maxRunning() + "）");
        }
    }

    /** 释放并发占位（受理失败回滚 / PENDING 取消） */
    public void releaseRunning(String tenantId) {
        kv.decrFloorZero("tenant:running:" + tenantId);
    }

    /** 分发包下载留痕（FREE 价值分析数据源之一） */
    public void countPackageDownload(String skillCode) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        kv.incr("skill:download:" + skillCode + ":" + day, Duration.ofHours(25));
    }
}
