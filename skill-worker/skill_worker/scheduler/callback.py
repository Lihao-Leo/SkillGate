"""回调通道（§5.5）：HMAC 签名体构造 + 退避重试（1/5/15min 延迟消息）+ 死信。

回调可能重复投递（重试导致），调用方须按 taskId 幂等处理；
签名：X-Skill-Signature = HMAC-SHA256(AppSecret, timestamp + body)，容差 5min。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Protocol

from ..domain import Execution, RunResult, Usage

HEADER_SIGNATURE = "X-Skill-Signature"
HEADER_TIMESTAMP = "X-Skill-Timestamp"


def callback_body(execution: Execution, result: RunResult, artifacts: list[dict],
                  charged: int, settled: bool, usage: Usage,
                  duration_ms: int | None = None, now: datetime | None = None) -> dict:
    """终态结果体：与轮询 GET /executions/{taskId} 响应同构（§5.4/§5.5）。"""
    timestamp = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "taskId": execution.task_id,
        "skillCode": execution.skill_code,
        "resolvedVersion": execution.skill_version,
        "status": "SUCCEEDED" if result.ok else ("CANCELLED" if result.error_code == "CANCELLED" else "FAILED"),
        "expectedCount": execution.expected_count,
        "artifacts": artifacts,
        "billing": {
            "mode": execution.pricing_mode,
            "pointsCharged": charged,
            "holdId": execution.hold_id,
            "settled": settled,
        },
        "context": execution.context_json(),
        "modelCalls": usage.model_calls,
        "tokensUsed": usage.tokens,
        "durationMs": duration_ms,
        "error": None if result.ok else {"code": result.error_code, "message": result.error_message},
        "timestamp": timestamp,
    }


def sign(secret: str, timestamp: str, body_json: str) -> str:
    return hmac.new(secret.encode(), (timestamp + body_json).encode(),
                    hashlib.sha256).hexdigest()


def verify(secret: str, timestamp: str, body_json: str, signature: str,
           now_millis: int | None = None, skew_millis: int = 5 * 60 * 1000) -> bool:
    """调用方验签参考实现（与 gateway 请求签名对称；测试复用）。"""
    try:
        ts = int(timestamp)
    except (TypeError, ValueError):
        return False
    if now_millis is not None and abs(now_millis - ts) > skew_millis:
        return False
    return hmac.compare_digest(sign(secret, timestamp, body_json), signature.lower())


class HttpPort(Protocol):
    def post_json(self, url: str, body_json: str, headers: dict[str, str],
                  timeout_seconds: float) -> int:
        """返回 HTTP 状态码；传输异常抛 IOError"""
        ...


class UrllibHttpPort:
    def post_json(self, url: str, body_json: str, headers: dict[str, str],
                  timeout_seconds: float) -> int:
        request = urllib.request.Request(url, data=body_json.encode(), method="POST", headers={
            "Content-Type": "application/json", **headers})
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code


class CallbackWorker:
    """skill-callback-retry 消费者：HTTP POST 回调 → 成功 SENT；失败退避重发；3 次进死信。"""

    def __init__(self, http: HttpPort, mq, execution_repo, app_key_repo, crypto_key: str,
                 timeout_seconds: float = 10.0, max_attempts: int = 3,
                 delay_levels: tuple = (5, 9, 14)):
        self._http = http
        self._mq = mq
        self._executions = execution_repo
        self._app_keys = app_key_repo
        self._crypto_key = crypto_key
        self._timeout = timeout_seconds
        self._max_attempts = max_attempts
        self._delay_levels = delay_levels

    def send_now(self, execution: Execution, body: dict) -> bool | None:
        """投递一次：True=成功；False=可重试失败；None=不可重试（直接死信）。"""
        cipher = self._app_keys.secret_cipher(execution.app_key_id)
        if not cipher:
            # 无凭据（数据异常）：直接死信并告警，不做无签名投递
            return None
        from ..adapters.crypto import decrypt

        secret = decrypt(self._crypto_key, cipher)
        body_json = json.dumps(body, ensure_ascii=False)
        timestamp = str(int(time.time() * 1000))
        headers = {
            HEADER_SIGNATURE: sign(secret, timestamp, body_json),
            HEADER_TIMESTAMP: timestamp,
        }
        try:
            status_code = self._http.post_json(execution.callback_url, body_json, headers,
                                               self._timeout)
        except IOError:
            return False
        return 200 <= status_code < 300

    def handle(self, message: dict) -> None:
        """回调重试消息处理：{taskId, body, attempt}。"""
        task_id = message["taskId"]
        attempt = int(message.get("attempt", 1))
        body = message["body"]
        execution = self._executions.get(task_id)
        if execution is None or not execution.callback_url:
            return
        sent = self.send_now(execution, body)
        if sent is True:
            self._executions.set_callback_status(task_id, "SENT")
            return
        if sent is None:
            self._mq.send("skill-callback-dlq", {"taskId": task_id, "attempt": attempt,
                                                 "callbackUrl": execution.callback_url,
                                                 "reason": "missing app secret"})
            self._executions.set_callback_status(task_id, "DEAD_LETTER")
            return
        if attempt >= self._max_attempts:
            # 超限进死信并告警（§5.5：指数退避 3 次，超限进死信）
            self._mq.send("skill-callback-dlq", {"taskId": task_id, "attempt": attempt,
                                                 "callbackUrl": execution.callback_url})
            self._executions.set_callback_status(task_id, "DEAD_LETTER")
            return
        next_attempt = attempt + 1
        self._executions.set_callback_status(task_id, "RETRYING")
        # attempt1 失败 → 1min 后重投 attempt2；attempt2 失败 → 5min 后 attempt3（1/5/15 退避）
        delay_level = self._delay_levels[min(attempt - 1, len(self._delay_levels) - 1)]
        self._mq.send("skill-callback-retry",
                      {"taskId": task_id, "body": body, "attempt": next_attempt},
                      delay_level=delay_level)
