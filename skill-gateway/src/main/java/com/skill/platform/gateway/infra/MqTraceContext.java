package com.skill.platform.gateway.infra;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

/**
 * MQ 消息 trace 上下文透传（技术方案 v2.0 §1.5）：发送前把 W3C traceparent 写入
 * message property，消费侧（worker / billing）extract 后接续同一 trace。
 * propagator 取自容器内 OpenTelemetry bean（starter 不注册全局，见 Tracing 说明）。
 * 无活跃 span 时 inject 为 no-op，消息保持干净。
 */
@Component
public class MqTraceContext {

    /** traceparent 注入的 message property 名（与 worker mq.py / billing 消费侧约定一致） */
    public static final String PROP_TRACEPARENT = "traceparent";

    private static final TextMapSetter<Message> SETTER =
            (message, key, value) -> message.getProperties().put(key, value);

    private final io.opentelemetry.context.propagation.TextMapPropagator propagator;

    public MqTraceContext(io.opentelemetry.api.OpenTelemetry openTelemetry) {
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    /** 把当前上下文的 traceparent 注入消息属性（无 span 时 no-op） */
    public void inject(Message message) {
        propagator.inject(Context.current(), message, SETTER);
    }
}
