package com.skill.platform.billing.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skill.platform.billing.dto.SettleRequest;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.service.BillingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * skill-settle 兜底消费（技术方案 v6.5 §3.3）：settle RPC 双次失败时 worker 发 MQ，
 * at-least-once 语义，按 taskId 幂等消化重复（hold 状态机保证不二次扣/退）。
 *
 * <p>生产开启：{@code skill-billing.mq.enabled=true}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "skill-billing.mq", name = "enabled", havingValue = "true")
public class SettleMessageConsumer implements SmartLifecycle {

    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    @Value("${skill-billing.mq.name-server}")
    private String nameServer;
    @Value("${skill-billing.mq.consumer-group:billing_settle_group}")
    private String consumerGroup;
    @Value("${skill-billing.mq.topic:skill-settle}")
    private String topic;

    private DefaultMQPushConsumer consumer;
    private volatile boolean running;

    public SettleMessageConsumer(BillingService billingService, ObjectMapper objectMapper) {
        this.billingService = billingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(consumerGroup);
            consumer.setNamesrvAddr(nameServer);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            consumer.subscribe(topic, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var message : messages) {
                    if (!consumeOne(message.getBody())) {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            running = true;
            log.info("settle consumer started: topic={}, group={}", topic, consumerGroup);
        } catch (Exception e) {
            throw new IllegalStateException("start settle consumer failed", e);
        }
    }

    private boolean consumeOne(byte[] body) {
        try {
            SettleRequest request = objectMapper.readValue(body, SettleRequest.class);
            SettleResponse response = billingService.settle(request.taskId(), request.actualPoints());
            log.info("settle message consumed: taskId={}, charged={}, alreadySettled={}",
                    request.taskId(), response.pointsCharged(), response.alreadySettled());
            return true;
        } catch (Exception e) {
            // 账户/冻结单暂时不可见等可重试错误交给 MQ 重试；不可恢复错误会进死信并告警
            log.error("settle message consume failed, will retry", e);
            return false;
        }
    }

    @Override
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
