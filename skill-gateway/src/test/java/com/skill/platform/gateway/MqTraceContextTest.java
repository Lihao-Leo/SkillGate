package com.skill.platform.gateway;

import com.skill.platform.gateway.infra.MqTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ 上下文透传注入测试（TC-TR-004 发送侧）：当前 span 存在时 message property 写入
 * W3C traceparent；无活跃 span 时不注入（保持 no-op 语义）。
 * 直接构造 OpenTelemetrySdk（W3C propagators），不经全局注册——与生产装配路径同语义。
 */
class MqTraceContextTest {

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
    private final MqTraceContext mqTraceContext = new MqTraceContext(OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build());

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void injects_traceparent_from_current_span() {
        Span span = tracerProvider.get("test").spanBuilder("producer-op").startSpan();
        try (Scope scope = span.makeCurrent()) {
            Message message = new Message("skill-execute", "task_1".getBytes());

            mqTraceContext.inject(message);

            String traceparent = message.getProperty("traceparent");
            assertThat(traceparent).isNotNull();
            assertThat(traceparent).startsWith("00-");
            // traceId 与当前 span 一致（32 hex）
            assertThat(traceparent.split("-")[1]).isEqualTo(span.getSpanContext().getTraceId());
        } finally {
            span.end();
        }
    }

    @Test
    void no_current_span_keeps_message_clean() {
        Message message = new Message("skill-execute", "task_2".getBytes());

        mqTraceContext.inject(message);

        assertThat(message.getProperty("traceparent")).isNull();
    }
}
