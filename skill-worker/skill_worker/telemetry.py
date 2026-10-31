"""OpenTelemetry 链路追踪接入（技术方案 v2.0 §1.7）：可选启用，未配置时 no-op。

启用条件：OTEL_EXPORTER_OTLP_ENDPOINT 已配置且 OTEL_SDK_DISABLED 未置真。
观测依赖不得成为故障源——初始化失败仅降级为 no-op 并告警，主链路不受影响。
跨服务上下文透传（MQ property / job env / HTTP header）统一使用 opentelemetry.propagate
的 W3C 默认实现，由 adapters/mq.py 与 scheduler/orchestrator.py 调用。
"""

from __future__ import annotations

import logging
import os

logger = logging.getLogger(__name__)

# 已初始化的全局 TracerProvider（供测试断言与优雅关闭）
_provider = None


def init_tracing(endpoint: str | None = None, service_name: str | None = None,
                 sdk_disabled: str | None = None) -> None:
    """初始化全局 TracerProvider；未配置 endpoint 或显式禁用时保持 no-op。

    endpoint 形如 http://127.0.0.1:4317（OTLP gRPC）；重复调用安全（先关旧 provider）。
    依赖缺失（未安装 opentelemetry）时降级为 no-op 并告警——保证裸环境可跑。
    """
    global _provider
    _reset()
    disabled = sdk_disabled if sdk_disabled is not None else os.environ.get("OTEL_SDK_DISABLED", "")
    if disabled.strip().lower() in ("1", "true", "yes"):
        logger.info("otel tracing disabled (OTEL_SDK_DISABLED)")
        return
    ep = (endpoint if endpoint is not None else os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "")).strip()
    if not ep:
        logger.info("otel tracing disabled (OTEL_EXPORTER_OTLP_ENDPOINT 未配置)")
        return
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        service = service_name or os.environ.get("OTEL_SERVICE_NAME", "skill-worker")
        provider = TracerProvider(resource=Resource.create({"service.name": service}))
        # 5s 导出超时：观测通道抖动不拖垮调度主进程
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=ep, timeout=5)))
        trace.set_tracer_provider(provider)
        _provider = provider
        logger.info("otel tracing enabled: service=%s endpoint=%s", service, ep)
    except ImportError:
        logger.warning("opentelemetry 依赖未安装，tracing 保持 no-op")


def tracer():
    """全局 tracer（stage span 与 MQ 透传共用）；未初始化时返回 OTel API no-op 代理。"""
    from opentelemetry import trace
    return trace.get_tracer("skill-worker")


def tracer_provider():
    """当前全局 TracerProvider；未启用时返回 None（测试与优雅关闭用）。"""
    return _provider


def current_trace_id() -> str | None:
    """当前 span 的 traceId（32 hex 小写）；未启用或无活跃 span 时返回 None。"""
    try:
        from opentelemetry import trace

        span_context = trace.get_current_span().get_span_context()
        if span_context.is_valid:
            return format(span_context.trace_id, "032x")
    except ImportError:
        pass
    return None


def current_traceparent() -> str | None:
    """按 W3C 格式从当前上下文取 traceparent（job env / 出站 header 注入用）；无则 None。"""
    try:
        from opentelemetry import propagate

        carrier: dict = {}
        propagate.inject(carrier)
        value = carrier.get("traceparent")
        return str(value) if value else None
    except ImportError:
        return None


def _reset() -> None:
    """关闭并清空已注册 provider（重复初始化与测试隔离用）。"""
    global _provider
    if _provider is not None:
        try:
            _provider.shutdown()
        except Exception:  # noqa: BLE001 - 关闭失败不影响重启初始化
            logger.warning("otel provider shutdown failed", exc_info=True)
    _provider = None
