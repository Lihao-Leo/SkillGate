package com.skill.platform.gateway.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local Noop 实现：仅记录日志（无 RocketMQ 环境）。
 * dev/prod 为 RocketMqExecuteMessageProducer。
 */
@Slf4j
@Component
@Profile("local")
public class NoopExecuteMessageProducer implements ExecuteMessageProducer {

    @Override
    public void sendExecute(String taskId) {
        log.info("[local] skill-execute message: taskId={}", taskId);
    }
}
