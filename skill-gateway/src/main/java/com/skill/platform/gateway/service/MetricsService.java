package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.dal.entity.Execution;
import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控看板指标（§8.2 / §10.1 执行监控）：基于 execution 表的轻量聚合。
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final ExecutionMapper executionMapper;
    private final com.skill.platform.gateway.dal.mapper.ModelCallMapper modelCallMapper;
    private final com.skill.platform.gateway.dal.mapper.AlertRecordMapper alertRecordMapper;

    /** 看板聚合（§10.1 执行监控实时视图）：吞吐/状态分布/Top5/回调/异常 */
    public Map<String, Object> dashboard(int hours) {
        return dashboard(hours, 5);
    }

    /** 监控看板（V2.1 扩展 topN：大屏 TOP skill 排行传 10）。 */
    public Map<String, Object> dashboard(int hours, int topN) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("queueDepth", count(Execution.STATUS_PENDING, null));
        board.put("running", count(Execution.STATUS_RUNNING, null));

        List<Double> ages = new ArrayList<>();
        for (Execution e : executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getStatus, Execution.STATUS_PENDING)
                .select(Execution::getCreatedAt))) {
            if (e.getCreatedAt() != null) {
                ages.add(java.time.Duration.between(e.getCreatedAt(), LocalDateTime.now()).toSeconds() / 60.0);
            }
        }
        ages.sort(Double::compareTo);
        board.put("pendingAgeP99Minutes", ages.isEmpty() ? 0.0
                : Math.round(ages.get((int) Math.ceil(0.99 * ages.size()) - 1) * 10.0) / 10.0);

        long succeeded = count(Execution.STATUS_SUCCEEDED, dayStart());
        long failed = count(Execution.STATUS_FAILED, dayStart());
        long finished = succeeded + failed;
        board.put("successRateToday", finished == 0 ? null
                : Math.round(succeeded * 1000.0 / finished) / 10.0);
        long insufficient = countErrorToday("40201", dayStart());
        board.put("insufficientPointsToday", insufficient);
        board.put("insufficientRatioToday", finished + insufficient == 0 ? null
                : Math.round(insufficient * 1000.0 / (finished + insufficient)) / 10.0);

        // 失败原因 Top5（按 error_code 聚合）：配合任务流水线，运营定位高频失败
        Map<String, Long> failureCount = new LinkedHashMap<>();
        for (Execution e : executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getStatus, Execution.STATUS_FAILED)
                .ge(Execution::getCreatedAt, since)
                .select(Execution::getErrorCode))) {
            if (e.getErrorCode() != null && !e.getErrorCode().isBlank()) {
                failureCount.merge(e.getErrorCode(), 1L, Long::sum);
            }
        }
        board.put("failureTop", failureCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(en -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("errorCode", en.getKey());
                    row.put("count", en.getValue());
                    return row;
                })
                .toList());

        // 24h 吞吐：受理（created_at）/完成（finished_at）按小时桶
        List<Execution> recent = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .ge(Execution::getCreatedAt, since)
                .select(Execution::getCreatedAt, Execution::getStatus, Execution::getFinishedAt,
                        Execution::getSkillCode));
        LocalDateTime hourStart = LocalDateTime.now().minusHours(hours).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        int buckets = hours;
        long[] accepted = new long[buckets];
        long[] completed = new long[buckets];
        for (Execution e : recent) {
            if (e.getCreatedAt() != null) {
                int idx = (int) java.time.Duration.between(hourStart, e.getCreatedAt()).toHours();
                if (idx >= 0 && idx < buckets) accepted[idx]++;
            }
            if (Execution.isTerminal(e.getStatus()) && e.getFinishedAt() != null) {
                int idx = (int) java.time.Duration.between(hourStart, e.getFinishedAt()).toHours();
                if (idx >= 0 && idx < buckets) completed[idx]++;
            }
        }
        List<Map<String, Object>> throughput = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            LocalDateTime t = hourStart.plusHours(i);
            Map<String, Object> bucket = new HashMap<>();
            bucket.put("hour", t.getHour() + ":00");
            bucket.put("accepted", accepted[i]);
            bucket.put("completed", completed[i]);
            throughput.add(bucket);
        }
        board.put("throughput", throughput);

        // 今日状态分布
        Map<String, Object> statusCounts = new LinkedHashMap<>();
        statusCounts.put("SUCCEEDED", succeeded);
        statusCounts.put("RUNNING", count(Execution.STATUS_RUNNING, dayStart()));
        statusCounts.put("FAILED", failed);
        statusCounts.put("PENDING", count(Execution.STATUS_PENDING, dayStart()));
        long pendingToday = count(Execution.STATUS_PENDING, dayStart());
        statusCounts.put("PENDING", pendingToday);
        board.put("statusCountsToday", statusCounts);
        board.put("totalToday", count(null, dayStart()));

        // Skill 用量 TopN（时间窗内，V2.1 大屏可传 topN=10）
        List<Map<String, Object>> top = new ArrayList<>();
        var topRows = executionMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Execution>()
                .select("skill_code", "COUNT(*) AS cnt")
                .ge("created_at", since)
                .groupBy("skill_code")
                .orderByDesc("cnt")
                .last("LIMIT " + Math.min(Math.max(topN, 1), 50)));
        for (var row : topRows) {
            top.add(Map.of("skillCode", String.valueOf(row.get("skill_code")),
                    "count", ((Number) row.get("cnt")).longValue()));
        }
        board.put("skillUsageTop", top);

        // V2.1 扩展：平均耗时（时间窗内终态任务）、token 用量与费用（model_call 聚合）、待处理告警数
        List<Execution> terminalRows = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .in(Execution::getStatus, Execution.STATUS_SUCCEEDED, Execution.STATUS_FAILED,
                        Execution.STATUS_CANCELLED)
                .ge(Execution::getCreatedAt, since)
                .select(Execution::getDurationMs));
        double avgDuration = terminalRows.stream()
                .mapToLong(e -> e.getDurationMs() == null ? 0L : e.getDurationMs()).average().orElse(0);
        board.put("avgDurationMsWindow", Math.round(avgDuration));
        String sinceText = since.toString().replace('T', ' ');
        board.put("tokensWindow", modelCallMapper.sumTokensSince(sinceText));
        board.put("costPointsWindow", modelCallMapper.sumCostSince(sinceText));
        board.put("pendingAlertCount", alertRecordMapper.selectCount(
                new LambdaQueryWrapper<com.skill.platform.gateway.dal.entity.AlertRecord>()
                        .eq(com.skill.platform.gateway.dal.entity.AlertRecord::getStatus,
                                com.skill.platform.gateway.dal.entity.AlertRecord.STATUS_PENDING)));

        // 回调成功率（终态任务，含 callback_status）
        long callbackSent = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getCallbackStatus, "SENT")
                .ge(Execution::getCreatedAt, since));
        long callbackFailed = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getCallbackStatus, "FAILED")
                .ge(Execution::getCreatedAt, since));
        board.put("callbackSent", callbackSent);
        board.put("callbackFailed", callbackFailed);
        board.put("callbackSuccessRate", callbackSent + callbackFailed == 0 ? null
                : Math.round(callbackSent * 1000.0 / (callbackSent + callbackFailed)) / 10.0);
        return board;
    }

    private LocalDateTime dayStart() {
        return LocalDate.now().atStartOfDay();
    }

    public Map<String, Object> overview() {
        return overviewMonitorData(LocalDateTime.now().minusHours(24));
    }

    /** 单 Skill 监控（V2.3）：汇总 + 按日趋势 + 版本分布（走 idx_skill_created）。 */
    public Map<String, Object> skillMonitor(String skillCode, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.min(Math.max(hours, 1), 720));
        Map<String, Object> result = new LinkedHashMap<>();

        long total = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getSkillCode, skillCode)
                .ge(Execution::getCreatedAt, since));
        long succeeded = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getSkillCode, skillCode)
                .eq(Execution::getStatus, Execution.STATUS_SUCCEEDED)
                .ge(Execution::getCreatedAt, since));
        long failed = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getSkillCode, skillCode)
                .eq(Execution::getStatus, Execution.STATUS_FAILED)
                .ge(Execution::getCreatedAt, since));

        List<Execution> rows = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getSkillCode, skillCode)
                .ge(Execution::getCreatedAt, since)
                .select(Execution::getDurationMs, Execution::getTokensUsed, Execution::getSkillVersion,
                        Execution::getStatus, Execution::getCreatedAt));

        double avgDuration = rows.stream()
                .mapToLong(e -> e.getDurationMs() == null ? 0L : e.getDurationMs()).average().orElse(0);
        double avgTokens = rows.stream()
                .mapToLong(e -> e.getTokensUsed() == null ? 0L : e.getTokensUsed()).average().orElse(0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("succeeded", succeeded);
        summary.put("failed", failed);
        summary.put("successRate", total == 0 ? null
                : Math.round(succeeded * 1000.0 / total) / 10.0);
        summary.put("avgDurationMs", Math.round(avgDuration));
        summary.put("avgTokens", Math.round(avgTokens));
        result.put("summary", summary);

        // 按日趋势（GROUP BY 表达式不带别名，H2/MySQL 双兼容）
        var dailyRows = executionMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Execution>()
                        .select("DATE(created_at) AS day",
                                "COUNT(*) AS total",
                                "SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded",
                                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed",
                                "COALESCE(AVG(duration_ms), 0) AS avgDurationMs")
                        .eq("skill_code", skillCode)
                        .ge("created_at", since)
                        .groupBy("DATE(created_at)")
                        .orderByAsc("DATE(created_at)"));
        result.put("daily", dailyRows);

        // 版本分布
        var versionRows = executionMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Execution>()
                        .select("skill_version",
                                "COUNT(*) AS cnt",
                                "SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded",
                                "COALESCE(AVG(duration_ms), 0) AS avgDurationMs")
                        .eq("skill_code", skillCode)
                        .ge("created_at", since)
                        .groupBy("skill_version")
                        .orderByDesc("cnt"));
        result.put("versions", versionRows);

        // 总成本（model_call 同窗聚合，走 idx_mc_skill_created）
        result.put("costPoints", modelCallMapper.sumCostBySkillSince(skillCode, since.toString().replace('T', ' ')));
        return result;
    }

    private Map<String, Object> overviewMonitorData(LocalDateTime since) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pending", count(Execution.STATUS_PENDING, null));
        metrics.put("running", count(Execution.STATUS_RUNNING, null));

        long succeeded = count(Execution.STATUS_SUCCEEDED, dayStart);
        long failed = count(Execution.STATUS_FAILED, dayStart);
        metrics.put("succeededToday", succeeded);
        metrics.put("failedToday", failed);
        long finished = succeeded + failed;
        metrics.put("successRateToday", finished == 0 ? null
                : Math.round(succeeded * 1000.0 / finished) / 10.0);

        // PENDING 年龄 P95（分钟）：队列积压观测（§8.2）
        List<Execution> pending = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getStatus, Execution.STATUS_PENDING)
                .select(Execution::getCreatedAt));
        metrics.put("pendingAgeP95Minutes", pendingAgeP95Minutes(pending));

        // 业务错误占比（今日）：40201=点数不足 42901=限流
        metrics.put("insufficientPointsToday", countErrorToday("40201", dayStart));
        metrics.put("rateLimitedToday", countErrorToday("42901", dayStart));
        return metrics;
    }

    private long count(String status, LocalDateTime since) {
        LambdaQueryWrapper<Execution> wrapper = new LambdaQueryWrapper<Execution>()
                .eq(Execution::getStatus, status);
        if (since != null) {
            wrapper.ge(Execution::getCreatedAt, since);
        }
        Long count = executionMapper.selectCount(wrapper);
        return count == null ? 0 : count;
    }

    private long countErrorToday(String code, LocalDateTime since) {
        Long count = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getErrorCode, code)
                .ge(Execution::getCreatedAt, since));
        return count == null ? 0 : count;
    }

    private Double pendingAgeP95Minutes(List<Execution> pending) {
        if (pending.isEmpty()) {
            return 0.0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Double> ages = pending.stream()
                .filter(e -> e.getCreatedAt() != null)
                .map(e -> java.time.Duration.between(e.getCreatedAt(), now).toSeconds() / 60.0)
                .sorted()
                .toList();
        if (ages.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(0.95 * ages.size()) - 1;
        return Math.round(ages.get(Math.max(0, index)) * 10.0) / 10.0;
    }
}
