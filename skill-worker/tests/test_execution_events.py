"""执行事件链 worker 侧测试（TC-TR-003 / TC-TR-007 / TC-TR-004 worker 侧）。"""

from __future__ import annotations

from opentelemetry import propagate, trace
from opentelemetry.sdk.trace import TracerProvider

from skill_worker.adapters.mq import consume_span, inject_trace
from skill_worker.telemetry import current_traceparent, init_tracing, tracer, tracer_provider
from tests.fakes import Harness


def teardown_function():
    init_tracing(None, None, "true")  # 测试间保持 no-op，避免跨用例残留 provider


def test_happy_path_reports_full_stage_sequence():
    """成功任务：CLAIM→STAGE→SANDBOX→COLLECT→ARTIFACT_PUT→SETTLE→FINALIZE 顺序完整。"""
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    h.sandbox.program("task_test_0001", "SUCCEEDED")
    h.sandbox.write_artifact("task_test_0001", "out.mp4", b"v")

    h.orchestrator.handle("task_test_0001")

    stages = h.events.stages_of("task_test_0001")
    assert stages == ["CLAIM", "STAGE", "SANDBOX", "COLLECT", "ARTIFACT_PUT", "SETTLE", "FINALIZE"]
    for event in h.events.events_of("task_test_0001"):
        assert event["service"] == "worker"
        assert event["status"] == "OK"
        assert event["taskId"] == "task_test_0001"
        assert event["tenantId"] == "tenant-a"
        assert event["occurredAt"] is not None
    settle = [e for e in h.events.events_of("task_test_0001") if e["stage"] == "SETTLE"][0]
    assert settle["detail"]["points"] == 10
    assert settle["detail"]["fallback"] is False
    finalize = [e for e in h.events.events_of("task_test_0001") if e["stage"] == "FINALIZE"][0]
    assert finalize["detail"]["final_status"] == "SUCCEEDED"


def test_failed_sandbox_reports_failed_stage_and_refund_settle():
    """失败任务（TC-TR-007）：SANDBOX FAILED 带错误码，SETTLE 全额退款 points=0。"""
    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_fail_0001")
    h.sandbox.program("task_fail_0001", "FAILED")

    h.orchestrator.handle("task_fail_0001")

    events = {e["stage"]: e for e in h.events.events_of("task_fail_0001")}
    assert events["SANDBOX"]["status"] == "FAILED"
    assert events["SANDBOX"]["detail"]["error_code"] == "INTERNAL"
    assert events["COLLECT"]["status"] == "FAILED"
    assert events["SETTLE"]["detail"]["points"] == 0  # 失败全额退款
    assert events["FINALIZE"]["detail"]["final_status"] == "FAILED"


def test_events_absent_when_reporter_not_wired():
    """events=None（旧装配路径）不报错、行为不变。"""
    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_norep")
    h.sandbox.program("task_norep", "SUCCEEDED")
    h.sandbox.write_artifact("task_norep", "a.txt", b"x")
    h.orchestrator._events = None  # noqa: SLF001 - 显式断开以覆盖缺省路径

    h.orchestrator.handle("task_norep")

    assert h.execution_row("task_norep")["status"] == "SUCCEEDED"


def test_traceparent_injected_into_sandbox_spec_env():
    """_build_spec 将当前 trace 上下文写入 spec.env.TRACEPARENT（TC-TR-004 沙箱透传）。"""
    init_tracing("http://127.0.0.1:4317", "skill-worker-test", None)
    assert tracer_provider() is not None

    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_tp_0001")
    execution = h.executions.get("task_tp_0001")

    with tracer().start_as_current_span("mq.consume"):
        spec = h.orchestrator._build_spec(execution)  # noqa: SLF001 - 测试访问内部装配

    assert spec.env.get("TRACEPARENT", "").startswith("00-")


def test_mq_inject_and_extract_continue_same_trace():
    """mq 透传（TC-TR-004）：inject 写 traceparent，consume_span 提取后同 traceId。"""
    init_tracing("http://127.0.0.1:4317", "skill-worker-test", None)
    assert tracer_provider() is not None

    with tracer().start_as_current_span("produce") as span:
        props: dict = {}
        inject_trace(props)
        assert props.get("traceparent", "").startswith("00-")
        producer_trace_id = format(span.get_span_context().trace_id, "032x")

    with consume_span(props):
        current = trace.get_current_span().get_span_context()
        assert current.is_valid
        assert format(current.trace_id, "032x") == producer_trace_id


def test_mq_helpers_noop_without_span():
    """无活跃 span：inject 不写；consume_span 退化为 no-op 不抛错。"""
    props: dict = {}
    inject_trace(props)
    assert props == {}
    with consume_span(None):
        pass  # 不抛错即可


def test_current_traceparent_helper_roundtrip():
    """telemetry.current_traceparent 与 inject 一致（job env / 出站 header 用）。"""
    init_tracing("http://127.0.0.1:4317", "skill-worker-test", None)
    assert tracer_provider() is not None

    with tracer().start_as_current_span("produce"):
        tp = current_traceparent()
    assert tp is not None
    assert tp.startswith("00-")
