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
from ..telemetry import current_trace_id, current_traceparent
from . import artifacts as artifact_rules
from . import alert_rules as alert_matchers
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
                 now_fn=time.time, events=None, notifier=None, alert_config=None,
                 model_call_reporter=None, alert_record_reporter=None):
        self._executions = executions
        self._artifacts = artifacts
        self._skills = skills
        self._sandbox = sandbox
        self._oss = oss
        self._kv = kv
        self._mq = mq
        self._settings = settings
        self._now = now_fn
        self._events = events
        self._notifier = notifier
        self._alert_config = alert_config
        self._model_calls = model_call_reporter
        self._alert_records = alert_record_reporter
        self._call_seq: dict[str, int] = {}
        self.settle_flow = SettleFlow(billing, mq, settings)

    # ------------------------------------------------------------------
    # 事件链（技术方案 v2.0 §1.3：stage 词表与 gateway/事件表一致）
    # ------------------------------------------------------------------

    def _event(self, execution: Execution, stage: str, status: str, duration_ms: float,
               detail: dict | None = None) -> None:
        """上报一条 worker 阶段事件；reporter 缺省（单测/未接线）或上报失败静默忽略。"""
        if self._events is None:
            return
        try:
            self._events.report({
                "taskId": execution.task_id,
                "tenantId": execution.tenant_id,
                "stage": stage,
                "status": status,
                "service": "worker",
                "durationMs": int(duration_ms) if duration_ms is not None else None,
                "detail": detail or None,
                "traceId": current_trace_id(),
                "occurredAt": datetime.now().isoformat(timespec="milliseconds"),
            })
        except Exception:  # noqa: BLE001 - 观测不阻塞执行主链路
            logger.warning("event report failed: task=%s stage=%s", execution.task_id, stage)

    def _alert(self, text: str, dedup_key: str | None = None, ttl_seconds: int = 86400) -> None:
        """飞书群告警（可选）；统一配置来源 = sys_config（client 热下发）→ env 兜底；
        dedup_key 命中已告警记录则跳过（防轰炸），TTL 按规则窗口（默认 86400）。"""
        self._refresh_alert_config()
        if self._notifier is None or not getattr(self._notifier, "enabled", False):
            return
        try:
            if dedup_key:
                flag = f"task:alerted:{dedup_key}"
                if self._kv.get(flag):
                    return
                self._kv.set(flag, "1", ttl_seconds=ttl_seconds)
            self._notifier.send_text(text)
        except Exception:  # noqa: BLE001 - 告警不阻塞执行主链路
            logger.warning("feishu alert failed: %s", dedup_key)

    def _record_alerts(self, rules: list[dict], execution: Execution, title: str,
                       content: str, detail: dict | None, notified: bool) -> None:
        """命中的规则逐条落库（gateway /internal/alert-records；uk 幂等刷 last_seen_at）。
        上报器缺省（单测/未接线）静默跳过。"""
        if self._alert_records is None or not rules:
            return
        occurred_at = datetime.now().isoformat(timespec="milliseconds")
        for rule in rules:
            try:
                self._alert_records.report(alert_matchers.build_record(
                    rule, execution.task_id, execution.skill_code, execution.tenant_id,
                    title, content, detail, notified, occurred_at))
            except Exception:  # noqa: BLE001 - 观测不阻塞执行主链路
                logger.warning("alert record report failed: task=%s rule=%s",
                               execution.task_id, rule.get("ruleCode"))

    def _fire_alert(self, rule_type: str, execution: Execution, value: float | None,
                    title: str, content: str, dedup_key: str, detail: dict | None = None) -> None:
        """V2.1 规则化告警入口：match_rules → 飞书（沿用文案）+ 落库记录。
        dedup 键 {taskId}:{ruleCode}，TTL 取规则窗口（legacy 兜底规则 86400）。"""
        rules = alert_matchers.match_rules(
            alert_matchers.effective_rules(self._alert_config, self._settings), rule_type,
            execution.skill_code, value)
        if not rules:
            return
        self._refresh_alert_config()
        notified = False
        for rule in rules:
            ttl = int(rule.get("dedupWindowSeconds") or 86400)
            self._alert(content, dedup_key=f"{dedup_key}:{rule.get('ruleCode')}", ttl_seconds=ttl)
            notified = True
        self._record_alerts(rules, execution, title, content, detail, notified)

    def _refresh_alert_config(self) -> None:
        """统一配置热生效：sys_config（管理后台系统配置页）→ 60s 缓存拉取 → env 兜底。"""
        if self._alert_config is None or self._notifier is None:
            return
        try:
            self._alert_config.refresh_into(self._notifier)
        except Exception:  # noqa: BLE001
            logger.warning("alert config refresh failed", exc_info=True)

    # ------------------------------------------------------------------
    # 消费入口（skill-execute）
    # ------------------------------------------------------------------

    def handle(self, task_id: str) -> None:
        handle_start = time.time()
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
        self._event(execution, "CLAIM", "OK", (time.time() - handle_start) * 1000)
        self._run(execution)

    # ------------------------------------------------------------------
    # 主流程
    # ------------------------------------------------------------------

    def _run(self, execution: Execution) -> None:
        started = time.time()
        stage_start = time.time()
        staging_error = self._stage_workspace(execution)
        if staging_error:
            self._event(execution, "STAGE", "FAILED", (time.time() - stage_start) * 1000,
                        {"error_code": "INTERNAL", "error_message": staging_error[:400]})
            self._finish(execution, RunResult.failed("INTERNAL", staging_error), [], Usage())
            return
        self._event(execution, "STAGE", "OK", (time.time() - stage_start) * 1000)

        spec = self._build_spec(execution)
        sandbox_start = time.time()
        job = self._sandbox.create_job(spec)
        result, cancelled = self._watch(job, execution, spec.timeout_seconds)
        sandbox_ms = (time.time() - sandbox_start) * 1000
        if cancelled:
            # 用户取消 → 杀 Job 走失败路径（§3.4）
            self._sandbox.delete_job(job)
            result = RunResult.cancelled()
        if result.ok:
            self._event(execution, "SANDBOX", "OK", sandbox_ms,
                        {"job_name": job, "progress": 100})
        else:
            self._event(execution, "SANDBOX", "FAILED", sandbox_ms,
                        {"job_name": job, "error_code": result.error_code,
                         "error_message": (result.error_message or "")[:400]})

        collect_start = time.time()
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
        self._event(execution, "COLLECT", "OK" if result.ok else "FAILED",
                    (time.time() - collect_start) * 1000,
                    {"file_count": len(files),
                     "error_code": None if result.ok else result.error_code})
        # V2.1：逐次模型调用明细上收（工作区 .usage/model_calls.jsonl → gateway model_call 表）
        call_entries = self._collect_model_calls(execution)
        usage = self._flush_usage(execution.task_id, fallback_entries=call_entries)
        # token 超限告警（V2.1 规则化：gateway 下发规则；无规则时 legacy alert.tokens 阈值兜底）
        self._fire_alert(
            alert_matchers.RULE_TOKEN_EXCESS, execution, usage.tokens,
            title=f"任务 token 超限：{execution.skill_code}",
            content=f"task={execution.task_id} skill={execution.skill_code} "
                    f"tokens={usage.tokens}（可在管理台强制终止）",
            dedup_key=execution.task_id,
            detail={"tokens": usage.tokens, "modelCalls": usage.model_calls})
        self._finish(execution, result, files, usage)

    def _finish(self, execution: Execution, result: RunResult, files: list[ArtifactFile],
                usage: Usage) -> None:
        """顺序不变量落地点：先落产物 → 再结算 → 最后置终态 → 投递回调。"""
        artifact_views: list[dict] = []
        if result.ok and files:
            # 1) 先落产物（{tenantId}/artifacts/{taskId}/）
            artifact_start = time.time()
            for file in files:
                key = f"{execution.tenant_id}/artifacts/{execution.task_id}/{file.filename}"
                self._oss.put(key, file.content)
            if not self._artifacts.exists(execution.task_id):
                self._artifacts.insert(execution.task_id, execution.tenant_id, files)
            self._event(execution, "ARTIFACT_PUT", "OK", (time.time() - artifact_start) * 1000,
                        {"file_count": len(files)})
            artifact_views = [
                {"type": file.type,
                 "url": self._oss.presign_get(
                     f"{execution.tenant_id}/artifacts/{execution.task_id}/{file.filename}"),
                 "sizeBytes": file.size}
                for file in files
            ]

        # 2) 同步结算（失败立即重试 1 次；双次失败走 MQ 兜底，charged 为预估）
        settle_start = time.time()
        actual = self.settle_flow.actual_points(execution, result, len(files), usage)
        charged, settled = self.settle_flow.settle(execution, actual)
        self._event(execution, "SETTLE", "OK", (time.time() - settle_start) * 1000,
                    {"points": charged, "fallback": not settled,
                     "hold_id": execution.hold_id})

        # 3) 最后置终态【无完成无结果竞态】
        duration_ms = int((time.time() - self._started_clock(execution)) * 1000)
        final_status = "SUCCEEDED" if result.ok else (
            "CANCELLED" if result.error_code == "CANCELLED" else "FAILED")
        self._executions.finalize(
            execution.task_id, final_status, result.error_code, result.error_message,
            duration_ms, usage.model_calls, usage.tokens, datetime.now(),
            callback_status=None if execution.callback_url else "NO_CALLBACK")
        self._event(execution, "FINALIZE", "OK", duration_ms,
                    {"final_status": final_status,
                     "error_code": result.error_code})
        # 失败告警（V2.1 规则化）：含失败码与扣点情况，便于群里直接感知成本异常
        if final_status == "FAILED":
            self._fire_alert(
                alert_matchers.RULE_TASK_FAILED, execution, duration_ms,
                title=f"任务失败：{execution.skill_code}（{result.error_code}）",
                content=f"task={execution.task_id} skill={execution.skill_code} "
                        f"err={result.error_code} 扣点={charged} tokens={usage.tokens} "
                        f"时长={duration_ms}ms\n{str(result.error_message or '')[:200]}",
                dedup_key=execution.task_id,
                detail={"errorCode": result.error_code, "points": charged,
                        "tokens": usage.tokens, "durationMs": duration_ms})

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
        # trace 上下文注入沙箱（技术方案 v2.0 §1.5）：toolset 出站调用接续同一 trace
        traceparent = current_traceparent()
        if traceparent:
            spec.env["TRACEPARENT"] = traceparent
        return spec

    def _watch(self, job: str, execution: Execution,
               timeout_seconds: int) -> tuple[RunResult, bool]:
        """轮询 Job 终态与 CANCELLING 标记；超时（含宽限）未终 → 交看门狗收殓。

        管理台强制终止：Redis 标志 task:force-cancel:{taskId} 置位时立即杀 Job
        （比等 DB 轮询更快，且 worker 重启后标志仍在）。
        """
        deadline = time.time() + timeout_seconds + 60
        while time.time() < deadline:
            # 技能上报进度（Redis）→ 落库（gateway 查询展示）
            progress = self._kv.get(f"task:progress:{execution.task_id}")
            if progress:
                try:
                    self._executions.update_progress(execution.task_id, int(progress))
                except (TypeError, ValueError):
                    pass
            if self._kv.get(f"task:force-cancel:{execution.task_id}"):
                self._event(execution, "WATCHDOG", "OK", 0,
                            {"action": "FORCE_CANCEL", "reason": "admin force-cancel flag"})
                return RunResult.cancelled(), True
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

    def _flush_usage(self, task_id: str, fallback_entries: list[dict] | None = None) -> Usage:
        """终态刷回聚合用量；Redis 聚合为空但逐次明细存在时按明细聚合兜底
        （修复 K8s 沙箱未注入 REDIS_URL 的聚合断链，METERED 结算随之受益）。"""
        usage = usage_rules.flush_usage(self._kv, task_id)
        if usage.model_calls == 0 and usage.tokens == 0 and fallback_entries:
            for entry in fallback_entries:
                kind = str(entry.get("kind") or "chat").lower()
                if kind == "video":
                    usage.video_calls += 1
                    continue
                usage.llm_calls += 1
                usage.llm_input_tokens += int(entry.get("promptTokens")
                                              or entry.get("prompt_tokens") or 0)
                usage.llm_output_tokens += int(entry.get("completionTokens")
                                               or entry.get("completion_tokens") or 0)
        return usage

    def _collect_model_calls(self, execution: Execution) -> list[dict]:
        """上收工作区逐次调用明细 → reporter（camelCase 契约）；返回原始明细供聚合兜底。
        上报器缺省（单测/未接线）或沙箱未实现（旧 FakeSandbox）静默跳过。"""
        try:
            entries = self._sandbox.collect_usage(execution.task_id)
        except Exception:  # noqa: BLE001 - 观测不阻塞主链路
            return []
        if not entries:
            return []
        if self._model_calls is not None:
            base_seq = self._call_seq.get(execution.task_id, 0)
            for entry in entries:
                base_seq += 1
                self._call_seq[execution.task_id] = base_seq
                try:
                    self._model_calls.report({
                        "taskId": execution.task_id,
                        "tenantId": execution.tenant_id,
                        "skillCode": execution.skill_code,
                        "seq": base_seq,
                        "kind": entry.get("kind") or "chat",
                        "alias": entry.get("alias") or "",
                        "modelName": entry.get("model") or "",
                        "promptTokens": int(entry.get("prompt_tokens") or 0),
                        "completionTokens": int(entry.get("completion_tokens") or 0),
                        "durationMs": entry.get("duration_ms"),
                        "status": entry.get("status") or "OK",
                        "errorCode": entry.get("error_code"),
                        "occurredAt": datetime.fromtimestamp(
                            (entry.get("ts") or time.time() * 1000) / 1000
                        ).isoformat(timespec="milliseconds"),
                    })
                except Exception:  # noqa: BLE001 - 观测不阻塞主链路
                    logger.warning("model call report failed: task=%s", execution.task_id)
        return entries

    def _started_clock(self, execution: Execution) -> float:
        return execution.started_at.timestamp() if execution.started_at else time.time()
