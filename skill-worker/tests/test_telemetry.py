"""telemetry 单元测试：no-op 与启用两条路径（无网络依赖——BatchSpanProcessor 异步导出不断言网络行为）。"""

from skill_worker import telemetry


def teardown_function():
    telemetry._reset()


def test_no_endpoint_keeps_noop(monkeypatch):
    """endpoint 未配置：不创建 provider（tracer 交由 otel 全局默认，单测环境可能有残留）。"""
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    monkeypatch.delenv("OTEL_SDK_DISABLED", raising=False)
    telemetry._reset()

    telemetry.init_tracing()

    assert telemetry.tracer_provider() is None


def test_sdk_disabled_flag_wins(monkeypatch):
    """OTEL_SDK_DISABLED 置真时即使配置了 endpoint 也保持 no-op。"""
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4317")
    monkeypatch.setenv("OTEL_SDK_DISABLED", "true")
    telemetry._reset()

    telemetry.init_tracing()

    assert telemetry.tracer_provider() is None


def test_enabled_creates_recording_provider(monkeypatch):
    """endpoint 已配置：创建 provider，tracer 产出可记录 span（服务名注入 resource）。"""
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4317")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "skill-worker-test")
    monkeypatch.delenv("OTEL_SDK_DISABLED", raising=False)
    telemetry._reset()

    telemetry.init_tracing()

    provider = telemetry.tracer_provider()
    assert provider is not None
    assert "skill-worker-test" in str(provider.resource.attributes["service.name"])
    span = telemetry.tracer().start_span("test.span")
    assert span.is_recording() is True
    span.end()
