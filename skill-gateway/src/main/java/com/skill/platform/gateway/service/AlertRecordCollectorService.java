package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.gateway.dal.entity.AlertRecord;
import com.skill.platform.gateway.dal.mapper.AlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警记录 collector（V2.1）：worker 触发告警的落库通道（观测语义——DB 故障丢弃不阻塞）。
 *
 * <p>同任务同规则幂等：uk(task_id, rule_code) 冲突时视为同告警复发，
 * 刷 last_seen_at；仅 PENDING 记录会重新打开（ACKED 不回退）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRecordCollectorService {

    public static final int MAX_BATCH = 100;

    private final AlertRecordMapper alertRecordMapper;
    private final ObjectMapper objectMapper;

    /** 上报输入；detail 为任意 JSON 可序列化对象。 */
    public record AlertRecordInput(String ruleCode, String ruleType, String severity,
                                   String taskId, String skillCode, String tenantId,
                                   String title, String content, Object detail,
                                   boolean notified, LocalDateTime occurredAt) {
    }

    /**
     * 批量落库告警记录。
     *
     * @return 实际落库条数（新记录数）；DB 故障返回 0
     */
    public int collectBatch(List<AlertRecordInput> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        if (records.size() > MAX_BATCH) {
            log.warn("alert_record 单批 {} 条超上限 {}，整批丢弃", records.size(), MAX_BATCH);
            return 0;
        }
        try {
            int inserted = 0;
            for (AlertRecordInput input : records) {
                inserted += upsert(input) ? 1 : 0;
            }
            return inserted;
        } catch (Exception dbError) {
            log.warn("alert_record 落库失败，本批 {} 条丢弃（观测不阻塞主链路）: {}",
                    records.size(), dbError.getMessage());
            return 0;
        }
    }

    /** 新记录插入返回 true；同任务同规则已存在则刷 last_seen_at 返回 false。 */
    private boolean upsert(AlertRecordInput input) {
        AlertRecord existing = alertRecordMapper.selectOne(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleCode, input.ruleCode() == null ? "" : input.ruleCode())
                .eq(input.taskId() != null, AlertRecord::getTaskId, input.taskId())
                .last("LIMIT 1"));
        if (existing != null) {
            AlertRecord update = new AlertRecord();
            update.setId(existing.getId());
            update.setLastSeenAt(LocalDateTime.now());
            alertRecordMapper.updateById(update);
            return false;
        }
        AlertRecord record = new AlertRecord();
        record.setRuleCode(input.ruleCode());
        record.setRuleType(input.ruleType() == null ? "UNKNOWN" : input.ruleType());
        record.setSeverity(input.severity() == null || input.severity().isBlank()
                ? "WARN" : input.severity());
        record.setStatus(AlertRecord.STATUS_PENDING);
        record.setTaskId(input.taskId());
        record.setSkillCode(input.skillCode());
        record.setTenantId(input.tenantId());
        record.setTitle(input.title() == null ? "告警" : input.title());
        record.setContent(input.content());
        record.setDetail(stringify(input.detail()));
        record.setNotified(input.notified() ? 1 : 0);
        record.setFirstSeenAt(input.occurredAt() == null ? LocalDateTime.now() : input.occurredAt());
        record.setLastSeenAt(LocalDateTime.now());
        alertRecordMapper.insert(record);
        return true;
    }

    private String stringify(Object detail) {
        if (detail == null) {
            return null;
        }
        if (detail instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException jsonError) {
            return String.valueOf(detail);
        }
    }
}
