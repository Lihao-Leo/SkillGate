"""故障恢复与看门狗（§4.10）：期望态（execution 终态）与实际态（MQ / K8s Job）持续对账收敛。

- PENDING 看门狗：>5min 未接手重投 MQ；>30min 置 FAILED(INTERNAL) + 全额退款
- RUNNING reconciler：超 timeout+5min 宽限查 Job 实际态收殓；missing 需连续 2 周期确认
  （防 API 抖动误判）→ delete Job（幂等）→ 条件回滚 PENDING → 重投（不变量接管）
- CANCELLING：杀 Job → 按失败路径结算 → 置 CANCELLED
- 各步幂等重入（采集按 artifact 表已存在跳过、结算按 hold 状态幂等、置终态条件更新）
"""

from __future__ import annotations

from datetime import datetime, timedelta

from ..adapters.k8s import JOB_SUCCEEDED, SandboxPort
from ..adapters.kv import KvPort
from ..adapters.mq import MqPort
from ..config import Settings
from ..domain import Execution, RunResult
from .orchestrator import Orchestrator

MISSING_STREAK_KEY = "watchdog:missing:{task_id}"


class Watchdog:
    def __init__(self, orchestrator: Orchestrator, sandbox: SandboxPort, kv: KvPort,
                 mq: MqPort, settings: Settings):
        self._orchestrator = orchestrator
        self._sandbox = sandbox
        self._kv = kv
        self._mq = mq
        self._settings = settings
        self._executions = orchestrator._executions  # noqa: SLF001 - 同模块协作

    def reconcile(self) -> None:
        self._requeue_stale_pending()
        self._fail_stuck_pending()
        self._recover_overdue_running()
        self._handle_cancelling()

    # ------------------------------------------------------------------
    # PENDING 段
    # ------------------------------------------------------------------

    def _requeue_stale_pending(self) -> None:
        cutoff = datetime.now() - timedelta(seconds=self._settings.pending_requeue_seconds)
        for execution in self._executions.pending_before(cutoff):
            self._mq.send("skill-execute", {"taskId": execution.task_id})  # 条件更新防双跑

    def _fail_stuck_pending(self) -> None:
        cutoff = datetime.now() - timedelta(seconds=self._settings.pending_fail_seconds)
        for execution in self._executions.pending_before(cutoff):
            # 置失败 + 全额退款（结算按 hold 状态幂等）
            self._orchestrator.settle_flow.settle(execution, 0)
            self._executions.finalize_pending_failed(execution.task_id)
            self._kv.delete(MISSING_STREAK_KEY.format(task_id=execution.task_id))

    # ------------------------------------------------------------------
    # RUNNING 段
    # ------------------------------------------------------------------

    def _recover_overdue_running(self) -> None:
        grace = timedelta(seconds=self._settings.running_grace_seconds)
        for execution in self._executions.running():
            if not execution.started_at:
                continue
            overdue_at = execution.started_at + timedelta(
                seconds=execution.timeout_seconds) + grace
            if datetime.now() < overdue_at:
                continue  # 仍在跑（宽限后下次再看）
            job_name = f"skill-{execution.task_id}"
            status = self._sandbox.job_status(job_name, execution.timeout_seconds)
            if status == JOB_SUCCEEDED:
                # scheduler watch 中崩溃：按 Job 实际终态收殓（幂等）
                self._orchestrator.recover_success(execution)
                self._kv.delete(MISSING_STREAK_KEY.format(task_id=execution.task_id))
                continue
            if status == "RUNNING":
                continue  # 仍在跑
            # missing / FAILED：连续 2 周期才动（防 API 抖动误判）
            streak = self._kv.incr(MISSING_STREAK_KEY.format(task_id=execution.task_id))
            if streak < 2:
                continue
            self._sandbox.delete_job(job_name)  # 幂等，防误判时产生双 Job
            rolled_back = self._executions.rollback_to_pending(execution.task_id)
            if rolled_back == 1:
                self._mq.send("skill-execute", {"taskId": execution.task_id})  # 消费端正常接手
            else:
                # 回滚竞争失败：收殓为 FAILED
                self._orchestrator.recover_failed(
                    execution, RunResult.failed("INTERNAL", "rollback race lost"))
            self._kv.delete(MISSING_STREAK_KEY.format(task_id=execution.task_id))

    # ------------------------------------------------------------------
    # CANCELLING 段
    # ------------------------------------------------------------------

    def _handle_cancelling(self) -> None:
        for execution in self._executions.cancelling():
            job_name = f"skill-{execution.task_id}"
            self._sandbox.delete_job(job_name)
            # 按失败路径结算（PER_EXECUTION 全退；METERED 按已发生用量）
            usage = self._orchestrator._flush_usage(execution.task_id)  # noqa: SLF001
            actual = self._orchestrator.settle_flow.actual_points(
                execution, RunResult.cancelled(), 0, usage)
            self._orchestrator.settle_flow.settle(execution, actual)
            self._executions.mark_cancelling_handled(execution.task_id)
