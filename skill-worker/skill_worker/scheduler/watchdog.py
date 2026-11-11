"""故障恢复与看门狗（§4.10）：期望态（execution 终态）与实际态（MQ / K8s Job）持续对账收敛。

- PENDING 看门狗：>5min 未接手重投 MQ；>30min 置 FAILED(INTERNAL) + 全额退款
- RUNNING reconciler：超 timeout+5min 宽限查 Job 实际态收殓；missing 需连续 2 周期确认
  （防 API 抖动误判）→ delete Job（幂等）→ 条件回滚 PENDING → 重投（不变量接管）
- CANCELLING：杀 Job → 按失败路径结算 → 置 CANCELLED
- 各步幂等重入（采集按 artifact 表已存在跳过、结算按 hold 状态幂等、置终态条件更新）
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta

from ..adapters.k8s import JOB_SUCCEEDED, SandboxPort
from ..adapters.kv import KvPort
from ..adapters.mq import MqPort
from ..config import Settings
from ..domain import Execution, RunResult
from . import alert_rules
from .orchestrator import Orchestrator

logger = logging.getLogger(__name__)

MISSING_STREAK_KEY = "watchdog:missing:{task_id}"


class Watchdog:
    def __init__(self, orchestrator: Orchestrator, sandbox: SandboxPort, kv: KvPort,
                 mq: MqPort, settings: Settings, events=None, notifier=None, alert_config=None,
                 alert_record_reporter=None):
        self._orchestrator = orchestrator
        self._sandbox = sandbox
        self._kv = kv
        self._mq = mq
        self._settings = settings
        self._events = events
        self._notifier = notifier
        self._alert_config = alert_config
        self._alert_records = alert_record_reporter
        self._executions = orchestrator._executions  # noqa: SLF001 - 同模块协作

    def reconcile(self) -> None:
        self._alert_long_running()
        self._requeue_stale_pending()
        self._fail_stuck_pending()
        self._recover_overdue_running()
        self._handle_cancelling()

    def _alert_long_running(self) -> None:
        """RUNNING 超阈值告警（V2.1 规则化：gateway 下发规则；无规则时 legacy
        alert.running-seconds 阈值兜底，行为与 V2.0 一致）。每任务每规则去重一次。"""
        rules = alert_rules.effective_rules(self._alert_config, self._settings)
        if not rules or self._notifier is None:
            return
        for execution in self._executions.running():
            if not execution.started_at:
                continue
            elapsed = (datetime.now() - execution.started_at).total_seconds()
            matched = alert_rules.match_rules(
                rules, alert_rules.RULE_RUNNING_TIMEOUT, execution.skill_code, elapsed)
            if not matched:
                continue
            title = f"任务长时间运行：{execution.skill_code}（{elapsed / 60:.0f} 分钟）"
            content = (f"⏰ 任务长时间运行 已 {elapsed / 60:.0f} 分钟 "
                       f"task={execution.task_id} skill={execution.skill_code} "
                       f"租户={execution.tenant_id} timeout={execution.timeout_seconds}s "
                       f"（可在管理台强制终止，终止按已发生用量结算）")
            for rule in matched:
                self._alert(content, dedup_key=f"{execution.task_id}:{rule.get('ruleCode')}",
                            ttl_seconds=int(rule.get("dedupWindowSeconds") or 86400))
            self._record_alerts(matched, execution, title, content,
                                {"elapsedSeconds": int(elapsed),
                                 "timeoutSeconds": execution.timeout_seconds})

    def _record_alerts(self, rules: list[dict], execution, title: str, content: str,
                       detail: dict) -> None:
        """命中的规则逐条落库（gateway /internal/alert-records）；上报器缺省静默跳过。"""
        if self._alert_records is None:
            return
        for rule in rules:
            try:
                self._alert_records.report(alert_rules.build_record(
                    rule, execution.task_id, execution.skill_code, execution.tenant_id,
                    title, content, detail, notified=True,
                    occurred_at=datetime.now().isoformat(timespec="milliseconds")))
            except Exception:  # noqa: BLE001 - 观测不阻塞看门狗
                logger.warning("alert record report failed: task=%s rule=%s",
                               execution.task_id, rule.get("ruleCode"))

    def _alert(self, text: str, dedup_key: str | None = None, ttl_seconds: int = 86400) -> None:
        """看门狗侧告警（与 orchestrator._alert 同语义）：统一配置热下发 + 去重 + no-op。"""
        if self._alert_config is not None and self._notifier is not None:
            try:
                self._alert_config.refresh_into(self._notifier)
            except Exception:  # noqa: BLE001
                logger.warning("alert config refresh failed", exc_info=True)
        if self._notifier is None or not getattr(self._notifier, "enabled", False):
            return
        try:
            if dedup_key:
                flag = f"task:alerted:{dedup_key}"
                if self._kv.get(flag):
                    return
                self._kv.set(flag, "1", ttl_seconds=ttl_seconds)
            self._notifier.send_text(text)
        except Exception:  # noqa: BLE001 - 告警不阻塞看门狗
            logger.warning("watchdog alert failed: %s", dedup_key)

    def _watchdog_event(self, execution, action: str, detail: dict) -> None:
        """上报 WATCHDOG 干预事件 + 告警（V2.1 规则化：WATCHDOG_INTERVENTION 规则；
        REQUEUE 属例行重投不告警）。"""
        if self._events is not None:
            try:
                from ..telemetry import current_trace_id

                self._events.report({
                    "taskId": execution.task_id,
                    "tenantId": execution.tenant_id,
                    "stage": "WATCHDOG",
                    "status": "OK",
                    "service": "worker",
                    "durationMs": None,
                    "detail": dict(detail, action=action),
                    "traceId": current_trace_id(),
                    "occurredAt": datetime.now().isoformat(timespec="milliseconds"),
                })
            except Exception:  # noqa: BLE001
                pass
        if self._notifier is None or action != "FAIL_STUCK":
            return
        rules = alert_rules.match_rules(
            alert_rules.effective_rules(self._alert_config, self._settings),
            alert_rules.RULE_WATCHDOG_INTERVENTION, execution.skill_code, 1)
        if not rules:
            return
        title = f"看门狗干预：{execution.skill_code}（{action}）"
        content = (f"🛑 看门狗干预 [{action}] task={execution.task_id} "
                   f"skill={execution.skill_code} 租户={execution.tenant_id}")
        for rule in rules:
            self._alert(content, dedup_key=f"{execution.task_id}:{rule.get('ruleCode')}",
                        ttl_seconds=int(rule.get("dedupWindowSeconds") or 86400))
        self._record_alerts(rules, execution, title, content, dict(detail, action=action))

    # ------------------------------------------------------------------
    # PENDING 段
    # ------------------------------------------------------------------

    def _requeue_stale_pending(self) -> None:
        cutoff = datetime.now() - timedelta(seconds=self._settings.pending_requeue_seconds)
        for execution in self._executions.pending_before(cutoff):
            self._mq.send("skill-execute", {"taskId": execution.task_id})  # 条件更新防双跑
            self._watchdog_event(execution, "REQUEUE", {"cutoff": cutoff.isoformat()})

    def _fail_stuck_pending(self) -> None:
        cutoff = datetime.now() - timedelta(seconds=self._settings.pending_fail_seconds)
        for execution in self._executions.pending_before(cutoff):
            # 置失败 + 全额退款（结算按 hold 状态幂等）
            self._orchestrator.settle_flow.settle(execution, 0)
            self._executions.finalize_pending_failed(execution.task_id)
            self._kv.delete(MISSING_STREAK_KEY.format(task_id=execution.task_id))
            self._watchdog_event(execution, "FAIL_STUCK",
                                 {"error_code": "INTERNAL", "reason": "pending stuck"})

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
