package com.skill.platform.gateway.service;

import com.skill.platform.gateway.dal.mapper.ExecutionMapper;
import com.skill.platform.gateway.infra.KvStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 租户并发计数漂移修复（每 1min）：Redis tenant:running:{tenantId} 以 DB 真值
 * （PENDING/RUNNING/CANCELLING 聚合）重置——受理侧获取占位、终态由 worker 落库，
 * 中间的异常路径（如副本宕机）造成的漂移在此收敛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "skill-platform.running-repair", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RunningCounterRepairJob {

    private final ExecutionMapper executionMapper;
    private final KvStore kv;

    @Scheduled(cron = "${skill-platform.running-repair.cron:30 * * * * ?}")
    public void repair() {
        try {
            List<Map<String, Object>> rows = executionMapper.countRunningByTenant();
            for (Map<String, Object> row : rows) {
                String tenantId = String.valueOf(row.get("tenantId"));
                long running = ((Number) row.get("running")).longValue();
                kv.set("tenant:running:" + tenantId, String.valueOf(running), Duration.ofHours(26));
            }
        } catch (Exception e) {
            log.error("running counter repair failed", e);
        }
    }
}
