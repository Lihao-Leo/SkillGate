package com.skill.platform.gateway.infra;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 生产实现（dev/prod profile）：skill-execute topic，同步发送，
 * 可感知失败由受理侧当场回滚（删 execution + 释放 hold + 50001，§4.10）。
 */
@Slf4j
@Component
@Profile({"dev", "prod"})
public class RocketMqExecuteMessageProducer implements ExecuteMessageProducer, SmartLifecycle {

    private final String nameServer;
    private final String producerGroup;
    private final String topic;

    private DefaultMQProducer producer;
    private volatile boolean running;

    public RocketMqExecuteMessageProducer(
            @Value("${skill-platform.mq.name-server}") String nameServer,
            @Value("${skill-platform.mq.producer-group:gateway_execute_group}") String producerGroup,
            @Value("${skill-platform.mq.topic:skill-execute}") String topic) {
        this.nameServer = nameServer;
        this.producerGroup = producerGroup;
        this.topic = topic;
    }

    @Override
    public void sendExecute(String taskId) {
        try {
            Message message = new Message(topic, taskId.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(message);
            log.info("skill-execute sent: taskId={}, msgId={}, status={}",
                    taskId, result.getMsgId(), result.getSendStatus());
        } catch (Exception e) {
            throw new IllegalStateException("send skill-execute failed: " + taskId, e);
        }
    }

    @Override
    public void start() {
        try {
            producer = new DefaultMQProducer(producerGroup);
            producer.setNamesrvAddr(nameServer);
            producer.setSendMsgTimeout(5000);
            producer.start();
            running = true;
            log.info("execute producer started: topic={}, group={}", topic, producerGroup);
        } catch (Exception e) {
            throw new IllegalStateException("start execute producer failed", e);
        }
    }

    @Override
    public void stop() {
        if (producer != null) {
            producer.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
