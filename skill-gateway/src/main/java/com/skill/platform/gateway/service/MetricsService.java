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

    /** 看板聚合（§10.1 执行监控实时视图）：吞吐/状态分布/Top5/回调/异常 */
    public Map<String, Object> dashboard(int hours) {
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

        // Skill 用量 Top5（时间窗内）
        List<Map<String, Object>> top = new ArrayList<>();
        var topRows = executionMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Execution>()
                .select("skill_code", "COUNT(*) AS cnt")
                .ge("created_at", since)
                .groupBy("skill_code")
                .orderByDesc("cnt")
                .last("LIMIT 5"));
        for (var row : topRows) {
            top.add(Map.of("skillCode", String.valueOf(row.get("skill_code")),
                    "count", ((Number) row.get("cnt")).longValue()));
        }
        board.put("skillUsageTop", top);

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
