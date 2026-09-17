"""编排主循环（§3.4 伪代码逐行对齐）。

顺序不变量：先落产物 → 再结算 → 最后置终态【无完成无结果竞态】。
故障路径不变量（§4.10）：条件更新防双消费；watch 期间轮询 CANCELLING；
空产物 EMPTY_OUTPUT 全额退款；超限 OUTPUT_LIMIT；结算 RPC 失败立即重试 1 次，
双次失败走 skill-settle MQ 兜底（按 taskId 幂等），预估值随回调（settled=false）。
"""

from __future__ import annotations

import logging
import time
from datetime import datetime

from ..adapters.k8s import (JOB_FAILED, JOB_MISSING, JOB_SUCCEEDED,
                            SandboxPort, sandbox_spec)
from ..adapters.oss import OssPort
from ..adapters.repositories import ArtifactRepository, ExecutionRepository, SkillRepository
from ..adapters.billing import BillingError, BillingPort
from ..adapters.kv import KvPort
from ..adapters.mq import MqPort
from ..config import Settings
from ..domain import (CANCELLING, PENDING, RUNNING, ArtifactFile, Execution, RunResult, Usage)
from . import artifacts as artifact_rules
from . import usage as usage_rules
from .callback import callback_body

logger = logging.getLogger(__name__)


class SettleFlow:
    """结算流（§4.8）：同步 RPC 主路径 → 失败立即重试 1 次 → MQ 兜底 + 预估值。"""

    def __init__(self, billing: BillingPort, mq: MqPort, settings: Settings):
        self._billing = billing
        self._mq = mq
        self._settings = settings

    def actual_points(self, execution: Execution, result: RunResult, artifact_count: int,
                      usage: Usage) -> int:
        mode = execution.pricing_mode
        if mode == "PER_EXECUTION":
            if not result.ok:
                return 0  # 失败/取消/空产物/超限：全额退款
            expected = execution.expected_count or 1
            unit = int(execution.pricing_snapshot.get("points", 0))
            return unit * min(artifact_count, expected)  # 超产按 min 计费
        if mode == "METERED":
            cap = int(execution.pricing_snapshot.get("capPoints", 0))
            return usage_rules.metered_points(
                usage,
                self._settings.metered_default_cost_per_1k_input,
                self._settings.metered_default_cost_per_1k_output,
                self._settings.metered_default_cost_per_video_call,
                cap)
        return 0  # FREE 不应有 hold，防御性返回 0

    def settle(self, execution: Execution, actual_points: int) -> tuple[int, bool]:
        """返回 (charged, settled)；settled=False 时已投递 MQ 兜底，charged 为预估值。"""
        if not execution.hold_id:
            return 0, True
        attempts = self._settings.settle_rpc_retries + 1
        for _ in range(attempts):
            try:
                outcome = self._billing.settle(execution.task_id, actual_points)
                return outcome.points_charged, True
            except BillingError:
                continue
        self._mq.send("skill-settle",
                      {"taskId": execution.task_id, "actualPoints": actual_points})
        return actual_points, False


class Orchestrator:
    def __init__(self, executions: ExecutionRepository, artifacts: ArtifactRepository,
                 skills: SkillRepository, sandbox: SandboxPort, oss: OssPort, kv: KvPort,
                 mq: MqPort, billing: BillingPort, settings: Settings,
                 now_fn=time.time):
        self._executions = executions
        self._artifacts = artifacts
        self._skills = skills
        self._sandbox = sandbox
        self._oss = oss
        self._kv = kv
        self._mq = mq
        self._settings = settings
        self._now = now_fn
        self.settle_flow = SettleFlow(billing, mq, settings)

    # ------------------------------------------------------------------
    # 消费入口（skill-execute）
    # ------------------------------------------------------------------

    def handle(self, task_id: str) -> None:
        execution = self._executions.get(task_id)
        if execution is None:
            return  # 受理侧已回滚，忽略残留消息
        if execution.status != PENDING:
            return  # 幂等：已接手/已取消跳过
        claimed_at = datetime.now()
        if self._executions.claim_running(task_id, claimed_at) != 1:
            return  # 另一副本已接手（条件更新防双消费）
        execution.status = RUNNING
        execution.started_at = claimed_at
        self._run(execution)

    # ------------------------------------------------------------------
    # 主流程
    # ------------------------------------------------------------------

    def _run(self, execution: Execution) -> None:
        started = time.time()
        staging_error = self._stage_workspace(execution)
        if staging_error:
            self._finish(execution, RunResult.failed("INTERNAL", staging_error), [], Usage())
            return

        spec = self._build_spec(execution)
        job = self._sandbox.create_job(spec)
        result, cancelled = self._watch(job, execution, spec.timeout_seconds)
        if cancelled:
            # 用户取消 → 杀 Job 走失败路径（§3.4）
            self._sandbox.delete_job(job)
            result = RunResult.cancelled()

        files = self._sandbox.collect_artifacts(execution.task_id) if result.ok else []
        if result.ok and not files:
            result = RunResult.failed("EMPTY_OUTPUT",
                                      "process exited 0 but artifacts/ is empty")  # 空产物 → FAILED 全额退款
        if result.ok:
            over = artifact_rules.over_limit(
                files, execution.output_config_snapshot,
                self._settings.artifact_max_file_bytes,
                self._settings.artifact_max_total_bytes,
                self._settings.artifact_default_max_count)
            if over:
                result = RunResult.failed("OUTPUT_LIMIT", over)
                files = []  # 超限不落 OSS（滥用防护）
        self._finish(execution, result, files, self._flush_usage(execution.task_id))

    def _finish(self, execution: Execution, result: RunResult, files: list[ArtifactFile],
                usage: Usage) -> None:
        """顺序不变量落地点：先落产物 → 再结算 → 最后置终态 → 投递回调。"""
        artifact_views: list[dict] = []
        if result.ok and files:
            # 1) 先落产物（{tenantId}/artifacts/{taskId}/）
            for file in files:
                key = f"{execution.tenant_id}/artifacts/{execution.task_id}/{file.filename}"
                self._oss.put(key, file.content)
            if not self._artifacts.exists(execution.task_id):
                self._artifacts.insert(execution.task_id, execution.tenant_id, files)
            artifact_views = [
                {"type": file.type,
                 "url": self._oss.presign_get(
                     f"{execution.tenant_id}/artifacts/{execution.task_id}/{file.filename}"),
                 "sizeBytes": file.size}
                for file in files
            ]

        # 2) 同步结算（失败立即重试 1 次；双次失败走 MQ 兜底，charged 为预估）
        actual = self.settle_flow.actual_points(execution, result, len(files), usage)
        charged, settled = self.settle_flow.settle(execution, actual)

        # 3) 最后置终态【无完成无结果竞态】
        duration_ms = int((time.time() - self._started_clock(execution)) * 1000)
        final_status = "SUCCEEDED" if result.ok else (
            "CANCELLED" if result.error_code == "CANCELLED" else "FAILED")
        self._executions.finalize(
            execution.task_id, final_status, result.error_code, result.error_message,
            duration_ms, usage.model_calls, usage.tokens, datetime.now(),
            callback_status=None if execution.callback_url else "NO_CALLBACK")

        if execution.callback_url:
            body = callback_body(execution, result, artifact_views, charged, settled, usage,
                                 duration_ms=duration_ms)
            self._mq.send("skill-callback-retry",
                          {"taskId": execution.task_id, "body": body, "attempt": 1})

    # ------------------------------------------------------------------
    # 看门狗收殓复用（TC-SCH-004：scheduler 崩溃后 RUNNING reconciler 幂等收殓）
    # ------------------------------------------------------------------

    def recover_success(self, execution: Execution) -> None:
        files = self._sandbox.collect_artifacts(execution.task_id)
        result = RunResult.success() if files else RunResult.failed("EMPTY_OUTPUT", "recovered empty")
        usage = self._flush_usage(execution.task_id)
        self._finish(execution, result, files, usage)

    def recover_failed(self, execution: Execution, result: RunResult) -> None:
        usage = self._flush_usage(execution.task_id)
        self._finish(execution, result, [], usage)

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    def _stage_workspace(self, execution: Execution) -> str | None:
        """入参与包落工作区（§4.7 输入注入 / §4.6 拉包 sha256 校验）。"""
        try:
            if execution.input_ref:
                self._sandbox.stage_input(execution.task_id, self._oss.get(execution.input_ref))
            owner_tenant = self._skills.skill_tenant(execution.skill_code, execution.tenant_id) \
                or execution.tenant_id
            package_key = (f"skill/{owner_tenant}/{execution.skill_code}/"
                           f"{execution.skill_version}/skill.zip")
            package = self._oss.get(package_key)
            self._sandbox.stage_package(execution.task_id, package, execution.package_sha256)
            return None
        except Exception as error:  # noqa: BLE001 - 沙箱预备失败统一走 INTERNAL 失败路径
            logger.error("stage workspace failed: task=%s input_ref=%s error=%s",
                         execution.task_id, execution.input_ref, error)
            return f"stage workspace failed: {error}"

    def _build_spec(self, execution: Execution):
        abilities = self._skills.required_abilities(execution.skill_code, execution.tenant_id)
        needs_video = any("video" in ability for ability in abilities)
        image = (self._settings.sandbox_video_image if needs_video
                 else self._settings.sandbox_base_image)
        spec = sandbox_spec(execution, image, self._settings.workspace_root,
                            self._settings.litellm_base_url, self._settings.litellm_api_key,
                            ",".join(abilities) if abilities else "llm-text")
        if needs_video:
            spec.cpu, spec.memory = "4", "8Gi"  # 视频类 4C8G 档位（§4.6）
        return spec

    def _watch(self, job: str, execution: Execution,
               timeout_seconds: int) -> tuple[RunResult, bool]:
        """轮询 Job 终态与 CANCELLING 标记；超时（含宽限）未终 → 交看门狗收殓。"""
        deadline = time.time() + timeout_seconds + 60
        while time.time() < deadline:
            # 技能上报进度（Redis）→ 落库（gateway 查询展示）
            progress = self._kv.get(f"task:progress:{execution.task_id}")
            if progress:
                try:
                    self._executions.update_progress(execution.task_id, int(progress))
                except (TypeError, ValueError):
                    pass
            status = self._sandbox.job_status(job, timeout_seconds)
            if status == JOB_SUCCEEDED:
                return RunResult.success(), False
            if status == JOB_FAILED:
                elapsed = time.time() - (execution.started_at.timestamp()
                                         if execution.started_at else time.time())
                if elapsed >= timeout_seconds:
                    return RunResult.timeout(timeout_seconds), False
                message = "sandbox job failed"
                failure_log = getattr(self._sandbox, "failure_log", None)
                if failure_log:
                    tail = failure_log(execution.task_id)
                    if tail:
                        message = "sandbox job failed: " + tail[-400:]
                return RunResult.failed("INTERNAL", message), False
            if status == JOB_MISSING:
                return RunResult.failed("INTERNAL", "sandbox job missing during watch"), False
            if self._executions.status(execution.task_id) == CANCELLING:
                return RunResult.cancelled(), True
            time.sleep(self._settings.watch_poll_seconds)
        return RunResult.failed("INTERNAL", "watch deadline exceeded"), False

    def _flush_usage(self, task_id: str) -> Usage:
        return usage_rules.flush_usage(self._kv, task_id)

    def _started_clock(self, execution: Execution) -> float:
        return execution.started_at.timestamp() if execution.started_at else time.time()
