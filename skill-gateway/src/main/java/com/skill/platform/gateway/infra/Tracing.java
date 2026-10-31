package com.skill.platform.gateway.infra;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

/**
 * 链路追踪工具（技术方案 v2.0 §1.5）：stage span 创建与 traceId 读取。
 * OTel Spring starter 默认不注册 GlobalOpenTelemetry——必须注入容器内的
 * {@link OpenTelemetry} bean（自动装配的 OpenTelemetrySdk），静态全局调用是 no-op 陷阱。
 * stage 命名与 execution_event 词表一致，span 属性 task_id 供 Jaeger 反查。
 */
@Component
public class Tracing {

    /** span 属性：任务ID（Jaeger tag 反查锚点） */
    public static final String ATTR_TASK_ID = "task_id";

    private final Tracer tracer;

    public Tracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("skill-gateway");
    }

    /** 创建挂在当前上下文上的 stage 子 span 并打 task_id 属性（受理五关埋点用） */
    public Span span(String stage, String taskId) {
        return tracer.spanBuilder(stage)
                .setAttribute(ATTR_TASK_ID, taskId)
                .startSpan();
    }

    /** 当前上下文 traceId；无活跃 span 时返回 null（事件落库关联与前端 Jaeger 深链用） */
    public String currentTraceId() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : null;
    }
}
