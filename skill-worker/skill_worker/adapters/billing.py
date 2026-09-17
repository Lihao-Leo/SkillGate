"""billing-service 结算 RPC（§3.4：同步主路径，失败立即重试 1 次，双次失败走 MQ 兜底）。"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Protocol


class BillingError(RuntimeError):
    """结算调用失败（可重试语义；hold 不存在等终态错误由 already_settled 表达）。"""


class BillingPort(Protocol):
    def settle(self, task_id: str, actual_points: int):
        """返回 SettleOutcome；失败抛 BillingError（调用方决定重试/降级 MQ）。"""
        ...


class HttpBillingClient:
    """POST /internal/billing/settle（X-Internal-Token）。"""

    def __init__(self, base_url: str, internal_token: str, timeout_seconds: float = 5.0):
        self._base_url = base_url.rstrip("/")
        self._token = internal_token
        self._timeout = timeout_seconds

    def settle(self, task_id: str, actual_points: int):
        from ..domain import SettleOutcome

        body = json.dumps({"taskId": task_id, "actualPoints": actual_points}).encode()
        request = urllib.request.Request(
            f"{self._base_url}/internal/billing/settle", data=body, method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": self._token})
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                envelope = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            # billing 信封错误（如 40401 hold 不存在）→ 不可重试语义，交上层记录
            try:
                detail = json.loads(error.read().decode())
            except Exception:
                detail = {}
            raise BillingError(
                f"settle http {error.code}: {detail.get('code')} {detail.get('message')}") from None
        except Exception as error:
            raise BillingError(f"settle rpc failed: {error}") from None

        if envelope.get("code") != 0:
            raise BillingError(
                f"settle biz error: {envelope.get('code')} {envelope.get('message')}")
        data = envelope.get("data") or {}
        return SettleOutcome(
            points_charged=int(data.get("pointsCharged", 0)),
            status=str(data.get("status", "")),
            already_settled=bool(data.get("alreadySettled", False)),
        )
