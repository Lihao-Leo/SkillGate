"""编排主循环测试（§3.4 顺序不变量 / §9 用例 TC-SCH-001、TC-BIL-003/010、TC-ART-002）。"""

from __future__ import annotations

from datetime import datetime, timedelta

from tests.fakes import Harness


def test_happy_path_collect_settle_finalize_in_order():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    h.sandbox.program("task_test_0001", "SUCCEEDED")
    h.sandbox.write_artifact("task_test_0001", "out.mp4", b"video-bytes")
    h.sandbox.write_artifact("task_test_0001", "report.md", b"# report")
    h.kv.hincrby("task:usage:task_test_0001", "llm_calls", 3)
    h.kv.hincrby("task:usage:task_test_0001", "llm_input_tokens", 1000)
    h.kv.hincrby("task:usage:task_test_0001", "llm_output_tokens", 500)

    h.orchestrator.handle("task_test_0001")

    row = h.execution_row("task_test_0001")
    assert row["status"] == "SUCCEEDED"
    assert row["error_code"] is None
    assert row["model_calls"] == 3
    assert row["tokens_used"] == 1500
    assert row["duration_ms"] is not None
    # 先落产物：OSS + artifact 表
    assert h.artifact_count("task_test_0001") == 2
    assert b"video-bytes" in h.oss.objects["tenant-a/artifacts/task_test_0001/out.mp4"]
    # 再结算：单价 10 × min(2 实际, 2 期望) = 20
    assert h.billing.calls == [("task_test_0001", 20)]
    # 最后回调投递（无 callback_url → NO_CALLBACK）
    assert h.mq.messages_of("skill-callback-retry") == []
    assert row["callback_status"] == "NO_CALLBACK"


def test_callback_message_body_isomorphic_with_polling_contract():
    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_cb_0001", callback_url="https://cb.example.com/hook")
    h.sandbox.program("task_cb_0001", "SUCCEEDED")
    h.sandbox.write_artifact("task_cb_0001", "out.mp4", b"v")

    h.orchestrator.handle("task_cb_0001")

    messages = h.mq.messages_of("skill-callback-retry")
    assert len(messages) == 1
    body = messages[0]["body"]
    # §5.5 字段全集（终态响应与轮询同构）
    assert set(body) >= {"taskId", "skillCode", "resolvedVersion", "status", "expectedCount",
                         "artifacts", "billing", "context", "modelCalls", "tokensUsed",
                         "durationMs", "error", "timestamp"}
    assert body["billing"] == {"mode": "PER_EXECUTION", "pointsCharged": 10,
                               "holdId": "hold_1", "settled": True}
    assert body["artifacts"][0]["url"].startswith("https://oss.local/")
    assert body["context"] == {"session": "s1"}
    assert h.execution_row("task_cb_0001")["callback_status"] == "PENDING"


def test_double_consume_only_one_replica_claims():
    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_dup", status="RUNNING")  # 另一副本已接手

    h.orchestrator.handle("task_dup")

    assert not h.sandbox.jobs  # 未创建 Job
    assert h.billing.calls == []


def test_terminal_execution_ignores_residual_message():
    h = Harness()
    h.seed_execution(task_id="task_done", status="SUCCEEDED")
    h.orchestrator.handle("task_done")
    assert not h.sandbox.jobs


def test_cancel_during_watch_kills_job_and_refunds():
    h = Harness()
    h.seed_skill()
    h.seed_execution(task_id="task_cancel", callback_url="https://cb.example.com/hook")
    h.sandbox.program("task_cancel", "RUNNING")

    # watch 首次轮询触发期间用户取消（gateway 已置 CANCELLING）
    def flip_to_cancelling():
        h.db.execute("UPDATE execution SET status = 'CANCELLING' WHERE task_id = 'task_cancel'")

    original = h.sandbox.job_status
    h.sandbox.job_status = lambda job, timeout: (flip_to_cancelling(), original(job, timeout))[1]

    h.orchestrator.handle("task_cancel")

    row = h.execution_row("task_cancel")
    assert row["status"] == "CANCELLED"
    assert row["error_code"] == "CANCELLED"
    # PER_EXECUTION 取消 → 全额退款（actual=0）
    assert h.billing.calls == [("task_cancel", 0)]
    assert h.sandbox.deleted  # Job 已杀


def test_empty_output_fails_with_full_refund():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    h.sandbox.program("task_test_0001", "SUCCEEDED")  # exit 0 但无产物

    h.orchestrator.handle("task_test_0001")

    row = h.execution_row("task_test_0001")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "EMPTY_OUTPUT"
    assert h.billing.calls == [("task_test_0001", 0)]  # 全额退款
    assert h.artifact_count("task_test_0001") == 0


def test_output_count_limit_fails_without_upload():
    h = Harness(artifact_default_max_count=2)
    h.seed_skill()
    h.seed_execution(task_id="task_limit",
                     output_config_snapshot='{"countable":false}')
    h.sandbox.program("task_limit", "SUCCEEDED")
    for i in range(3):
        h.sandbox.write_artifact("task_limit", f"f{i}.txt", b"x")

    h.orchestrator.handle("task_limit")

    row = h.execution_row("task_limit")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "OUTPUT_LIMIT"
    assert h.artifact_count("task_limit") == 0
    assert h.billing.calls == [("task_limit", 0)]
    assert not any(key.startswith("tenant-a/artifacts/task_limit") for key in h.oss.objects)


def test_job_failed_marks_internal():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    h.sandbox.program("task_test_0001", "FAILED")
    h.orchestrator.handle("task_test_0001")
    row = h.execution_row("task_test_0001")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "INTERNAL"


def test_timeout_detected_from_elapsed_time():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    # 直接驱动 _run：模拟已 RUNNING 且超过 timeout 的执行（watch 已观察到 Job FAILED）
    h.db.execute("UPDATE execution SET status = 'RUNNING', started_at = ? "
                 "WHERE task_id = 'task_test_0001'",
                 (datetime.now() - timedelta(seconds=601),))  # 超过默认 timeout 600s
    execution = h.executions.get("task_test_0001")
    h.sandbox.program("task_test_0001", "FAILED")

    h.orchestrator._run(execution)

    row = h.execution_row("task_test_0001")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "TIMEOUT"
    assert h.billing.calls == [("task_test_0001", 0)]


def test_settle_rpc_failure_falls_back_to_mq_with_estimate():
    h = Harness()
    h.seed_skill()
    h.billing.failures_before_success = 99  # 双次失败
    h.seed_execution(task_id="task_settle_fail", callback_url="https://cb.example.com/hook")
    h.sandbox.program("task_settle_fail", "SUCCEEDED")
    h.sandbox.write_artifact("task_settle_fail", "out.mp4", b"v")

    h.orchestrator.handle("task_settle_fail")

    # 兜底消息按 taskId 幂等（skill-settle）
    settle_messages = h.mq.messages_of("skill-settle")
    assert settle_messages == [{"taskId": "task_settle_fail", "actualPoints": 10}]
    # 回调 settled=false 且 pointsCharged 为预估值（TC-BIL-010）
    callback = h.mq.messages_of("skill-callback-retry")[0]["body"]
    assert callback["billing"]["settled"] is False
    assert callback["billing"]["pointsCharged"] == 10
    # 终态不受结算降级影响
    assert h.execution_row("task_settle_fail")["status"] == "SUCCEEDED"


def test_settle_retry_once_succeeds():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    h.billing.failures_before_success = 1  # 首次失败，重试成功
    h.sandbox.program("task_test_0001", "SUCCEEDED")
    h.sandbox.write_artifact("task_test_0001", "out.mp4", b"v")

    h.orchestrator.handle("task_test_0001")

    assert h.execution_row("task_test_0001")["status"] == "SUCCEEDED"
    assert h.mq.messages_of("skill-settle") == []
    callback = h.mq.messages_of("skill-callback-retry")
    assert callback == []  # 无回调地址


def test_metered_charges_usage_capped():
    h = Harness()
    h.seed_skill(required_abilities='["video-gen"]')
    h.seed_execution(
        task_id="task_metered",
        pricing_snapshot='{"mode":"METERED","capPoints":50}',
        output_config_snapshot="{}")
    h.sandbox.program("task_metered", "SUCCEEDED")
    h.sandbox.write_artifact("task_metered", "v.mp4", b"v")
    h.kv.hincrby("task:usage:task_metered", "video_calls", 10)  # 10 × 10 点 > cap 50

    h.orchestrator.handle("task_metered")

    assert h.billing.calls == [("task_metered", 50)]  # 封顶


def test_stage_failure_fails_internally_without_settle_charge():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    # 包 sha 不匹配（stage_package 抛异常）
    h.oss.objects["skill/tenant-a/demo-skill/1.0.0/skill.zip"] = b"tampered-package"

    h.orchestrator.handle("task_test_0001")

    row = h.execution_row("task_test_0001")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "INTERNAL"
    assert "sha256 mismatch" in row["error_message"]
    assert h.billing.calls == [("task_test_0001", 0)]


def test_overproduced_artifacts_charged_by_min():
    h = Harness()
    h.seed_skill()
    h.seed_execution()
    # expected=2，实际产出 3 → 交付全部、按 min(3,2) 计费
    h.sandbox.program("task_test_0001", "SUCCEEDED")
    for i in range(3):
        h.sandbox.write_artifact("task_test_0001", f"v{i}.mp4", b"v")

    h.orchestrator.handle("task_test_0001")

    assert h.artifact_count("task_test_0001") == 3  # 超产照常交付
    assert h.billing.calls == [("task_test_0001", 20)]  # 10 × min(3, 2)
