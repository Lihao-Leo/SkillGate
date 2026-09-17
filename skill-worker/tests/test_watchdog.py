"""看门狗测试（§4.10 / TC-SCH-002~009）。"""

from __future__ import annotations

from datetime import datetime, timedelta

from tests.fakes import Harness


def test_pending_over_5min_requeued():
    h = Harness()
    h.seed_execution(task_id="task_pending", created_at=datetime.now() - timedelta(minutes=6))
    h.watchdog.reconcile()
    assert h.mq.messages_of("skill-execute") == [{"taskId": "task_pending"}]
    assert h.execution_row("task_pending")["status"] == "PENDING"  # 重投不双跑（条件更新接管）


def test_pending_under_5min_untouched():
    h = Harness()
    h.seed_execution(task_id="task_fresh", created_at=datetime.now() - timedelta(minutes=1))
    h.watchdog.reconcile()
    assert h.mq.messages_of("skill-execute") == []


def test_pending_over_30min_failed_with_refund():
    h = Harness()
    h.seed_execution(task_id="task_stuck",
                     created_at=datetime.now() - timedelta(minutes=31),
                     hold_id="hold_stuck")
    h.watchdog.reconcile()
    row = h.execution_row("task_stuck")
    assert row["status"] == "FAILED"
    assert row["error_code"] == "INTERNAL"
    assert h.billing.calls == [("task_stuck", 0)]  # 全额退款


def test_running_recovered_from_succeeded_job():
    h = Harness()
    h.seed_skill()
    # scheduler watch 中崩溃：RUNNING 且 Job 已成功（TC-SCH-004）
    h.seed_execution(task_id="task_crash", status="RUNNING",
                     started_at=datetime.now() - timedelta(minutes=30))
    h.sandbox.program("task_crash", "SUCCEEDED")
    h.sandbox.write_artifact("task_crash", "out.mp4", b"v")

    h.watchdog.reconcile()

    row = h.execution_row("task_crash")
    assert row["status"] == "SUCCEEDED"
    assert h.artifact_count("task_crash") == 1  # 补采集
    assert h.billing.calls == [("task_crash", 10)]  # 补结算


def test_running_still_alive_skipped():
    h = Harness()
    h.seed_execution(task_id="task_alive", status="RUNNING",
                     started_at=datetime.now() - timedelta(minutes=30))
    h.sandbox.program("task_alive", "RUNNING")
    h.watchdog.reconcile()
    assert h.execution_row("task_alive")["status"] == "RUNNING"
    assert h.mq.messages_of("skill-execute") == []


def test_missing_job_rolled_back_after_two_rounds():
    h = Harness()
    h.seed_execution(task_id="task_orphan", status="RUNNING",
                     started_at=datetime.now() - timedelta(minutes=30))
    h.sandbox.program("task_orphan", "MISSING")

    h.watchdog.reconcile()  # 第一周期：防抖不动
    assert h.execution_row("task_orphan")["status"] == "RUNNING"
    assert h.mq.messages_of("skill-execute") == []

    h.watchdog.reconcile()  # 第二周期：连续确认 → 回滚 PENDING + 重投
    row = h.execution_row("task_orphan")
    assert row["status"] == "PENDING"
    assert row["started_at"] is None
    assert h.mq.messages_of("skill-execute") == [{"taskId": "task_orphan"}]


def test_cancelling_kills_job_settles_and_finalizes():
    h = Harness()
    h.seed_execution(task_id="task_cxl", status="CANCELLING", hold_id="hold_cxl")
    h.sandbox.program("task_cxl", "RUNNING")

    h.watchdog.reconcile()

    row = h.execution_row("task_cxl")
    assert row["status"] == "CANCELLED"
    assert row["error_code"] == "CANCELLED"
    assert h.sandbox.deleted == ["skill-task_cxl"]  # Job 已杀
    assert h.billing.calls == [("task_cxl", 0)]  # PER_EXECUTION 全退


def test_cancelling_metered_charges_last_known_usage():
    h = Harness()
    h.seed_execution(task_id="task_cxl_m", status="CANCELLING",
                     pricing_snapshot='{"mode":"METERED","capPoints":100}',
                     output_config_snapshot="{}")
    h.kv.hincrby("task:usage:task_cxl_m", "video_calls", 3)  # 3 × 10 = 30 点（最后已知值）

    h.watchdog.reconcile()

    # METERED 被杀按已发生用量
    assert h.billing.calls == [("task_cxl_m", 30)]
    assert h.execution_row("task_cxl_m")["status"] == "CANCELLED"
