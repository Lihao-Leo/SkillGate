"""告警规则匹配（V2.1 纯函数）：规则由 gateway 下发（60s 缓存），触发判定在 worker。

规则为空时按 legacy 阈值构造等价规则（alert.running-seconds / alert.tokens + FAILED 必告警），
保证 V2.0 行为逐字节不变；规则存在时完全按规则走（legacy 阈值不再叠加）。
"""

from __future__ import annotations

RULE_RUNNING_TIMEOUT = "RUNNING_TIMEOUT"
RULE_TOKEN_EXCESS = "TOKEN_EXCESS"
RULE_TASK_FAILED = "TASK_FAILED"
RULE_PENDING_STUCK = "PENDING_STUCK"
RULE_CALLBACK_DEAD_LETTER = "CALLBACK_DEAD_LETTER"
RULE_WATCHDOG_INTERVENTION = "WATCHDOG_INTERVENTION"


def effective_rules(alert_config, settings=None) -> list[dict]:
    """生效规则集：gateway rules 优先；为空时按 legacy 阈值构造（行为与 V2.0 一致）。

    legacy 阈值来源两级：alert_config（sys_config 热下发）→ settings env 兜底。
    """
    rules = []
    if alert_config is not None:
        try:
            rules = alert_config.rules() or []
        except Exception:  # noqa: BLE001 - 配置拉取异常回退 legacy
            rules = []
    if rules:
        return rules

    def threshold(getter: str, settings_attr: str) -> int:
        if alert_config is not None:
            try:
                return int(getattr(alert_config, getter)())
            except Exception:  # noqa: BLE001
                return 0
        return int(getattr(settings, settings_attr, 0) or 0) if settings is not None else 0

    legacy: list[dict] = []
    running_seconds = threshold("running_seconds", "alert_running_seconds")
    if running_seconds > 0:
        legacy.append(_legacy_rule("legacy.running-timeout", RULE_RUNNING_TIMEOUT,
                                   running_seconds))
    tokens_threshold = threshold("tokens_threshold", "alert_tokens")
    if tokens_threshold > 0:
        legacy.append(_legacy_rule("legacy.token-excess", RULE_TOKEN_EXCESS, tokens_threshold))
    # 与 V2.0 行为一致：任务失败必告警（受 webhook 可用性约束；dedup 同任务单次）
    legacy.append(_legacy_rule("legacy.task-failed", RULE_TASK_FAILED, 0))
    return legacy


def match_rules(rules: list[dict], rule_type: str, skill_code: str | None,
                value: float | None) -> list[dict]:
    """规则匹配：type 相等 +（skill 未限定或相等）+ value > threshold（与 legacy 超限语义一致）。"""
    matched = []
    for rule in rules:
        if rule.get("ruleType") != rule_type:
            continue
        scoped = rule.get("skillCode")
        if scoped and skill_code and scoped != skill_code:
            continue
        threshold = rule.get("thresholdValue") or 0
        if value is None or value <= threshold:
            continue
        matched.append(rule)
    return matched


def build_record(rule: dict, task_id: str, skill_code: str | None, tenant_id: str | None,
                 title: str, content: str, detail: dict | None, notified: bool,
                 occurred_at: str) -> dict:
    """构造告警记录（camelCase，gateway /internal/alert-records 契约）。"""
    return {
        "ruleId": rule.get("ruleId"),
        "ruleCode": rule.get("ruleCode") or rule.get("ruleType"),
        "ruleType": rule.get("ruleType"),
        "severity": rule.get("severity") or "WARN",
        "taskId": task_id,
        "skillCode": skill_code,
        "tenantId": tenant_id,
        "title": title,
        "content": content,
        "detail": detail,
        "notified": notified,
        "occurredAt": occurred_at,
    }


def _legacy_rule(rule_code: str, rule_type: str, threshold: int) -> dict:
    return {
        "ruleId": None,
        "ruleCode": rule_code,
        "ruleType": rule_type,
        "skillCode": None,
        "thresholdValue": threshold,
        "severity": "WARN",
        "channels": "feishu",
        "dedupWindowSeconds": 86400,
    }
