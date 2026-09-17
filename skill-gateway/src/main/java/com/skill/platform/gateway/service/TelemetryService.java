package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillIoLog;
import com.skill.platform.gateway.dal.mapper.SkillIoLogMapper;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.service.MaterialDtos.TelemetryReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FREE 公开分发 Skill 埋点（§5.7 / §6.12）：X-Skill-Token（skill 级令牌）鉴权，
 * IO 摘要入 skill_io_log，供平台价值分析（样本尽力而为）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final SkillMapper skillMapper;
    private final SkillIoLogMapper skillIoLogMapper;

    public void report(String telemetryToken, TelemetryReport report) {
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getTelemetryToken, telemetryToken)
                .last("LIMIT 1"));
        if (skill == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "X-Skill-Token 无效");
        }
        if (report.status() == null || report.status().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 不能为空（SUCCEEDED/FAILED）");
        }
        SkillIoLog ioLog = new SkillIoLog();
        ioLog.setSkillCode(skill.getSkillCode());
        ioLog.setVersion(report.version());
        ioLog.setInput(report.input() == null ? null : JsonCodec.write(report.input()));
        ioLog.setOutput(report.output() == null ? null : JsonCodec.write(report.output()));
        ioLog.setStatus(report.status());
        ioLog.setDurationMs(report.durationMs());
        ioLog.setCallerHint(report.callerHint());
        skillIoLogMapper.insert(ioLog);
        log.debug("skill io telemetry: skill={}, status={}", skill.getSkillCode(), report.status());
    }
}
