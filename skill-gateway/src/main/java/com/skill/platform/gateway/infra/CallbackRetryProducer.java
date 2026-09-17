package com.skill.platform.gateway.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * skill-callback-retry 生产者（§5.5 重试通道）：异常处理台「重发」用——
 * 网关按回调契约（与查询响应同构）重建 body 投递，worker CallbackWorker 消费后补投。
 */
@Slf4j
@Component
public class CallbackRetryProducer implements SmartLifecycle {

    private final String nameServer;
    private final String producerGroup;
    private final ObjectMapper objectMapper;
    private DefaultMQProducer producer;
    private volatile boolean running;

    public CallbackRetryProducer(
            @Value("${skill-platform.mq.name-server}") String nameServer,
            @Value("${skill-platform.callback-retry.producer-group:gateway_callback_producer}") String producerGroup,
            ObjectMapper objectMapper) {
        this.nameServer = nameServer;
        this.producerGroup = producerGroup;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        try {
            producer = new DefaultMQProducer(producerGroup);
            producer.setNamesrvAddr(nameServer);
            producer.setSendMsgTimeout(5000);
            producer.start();
            running = true;
            log.info("callback-retry producer started");
        } catch (Exception e) {
            throw new IllegalStateException("callback-retry producer start failed", e);
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

    /** body：回调契约 JSON 字符串（与查询响应同构） */
    public void send(String taskId, String body, int attempt) {
        try {
            String payload = objectMapper.writeValueAsString(
                    java.util.Map.of("taskId", taskId, "body", body, "attempt", attempt));
            Message message = new Message("skill-callback-retry", payload.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(message);
            log.info("callback resend sent: taskId={}, attempt={}, msgId={}, status={}",
                    taskId, attempt, result.getMsgId(), result.getSendStatus());
        } catch (Exception e) {
            throw new IllegalStateException("send callback retry failed: " + taskId, e);
        }
    }
}
