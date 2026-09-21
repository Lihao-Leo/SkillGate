package com.skill.platform.billing.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * skill-settle 兜底消费关闭态提示（与 {@link SettleMessageConsumer} 互斥启用）：
 * 消费者未启用时启动打一条 WARN——settle RPC 双次失败的任务将只能依赖
 * 24h 对账兜底补结算（§4.8），期间查询/回调里的 billing 为预估值。
 *
 * <p>启用口径：dev/prod profile 显式 {@code skill-billing.mq.enabled=true}；
 * local（H2 自洽，无 MQ）保持 false。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "skill-billing.mq", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class SettleConsumerDisabledWarning {

    @PostConstruct
    public void warn() {
        log.warn("[CONFIG] skill-settle 兜底消费者未启用（skill-billing.mq.enabled=false/缺省）："
                + "settle RPC 双次失败的任务将无 MQ 重试通道，依赖 24h 对账兜底；"
                + "生产环境应在 profile 中显式开启 skill-billing.mq.enabled=true");
    }
}
