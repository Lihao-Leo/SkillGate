"""usage 上报（§4.5）：toolset 每次调用后增量上报 Redis task:usage:{taskId}（分类计数）。

降级口径：Redis 故障/驱逐时接受少收（平台对账告警兜底），上报失败不阻断 Skill。
"""

from __future__ import annotations

import os
from typing import Protocol

from ..adapters.kv import InMemoryKv, KvPort

USAGE_KEY = "task:usage:{task_id}"


class BudgetExceeded(RuntimeError):
    """单任务模型调用上限硬闸（§4.5：真跑飞了的闸门）。"""


class UsageReporter:
    def __init__(self, kv: KvPort, task_id: str, model_call_limit: int = 500):
        self._kv = kv
        self._key = USAGE_KEY.format(task_id=task_id)
        self._limit = model_call_limit

    def report_llm(self, input_tokens: int, output_tokens: int) -> None:
        self._kv.hincrby(self._key, "llm_calls", 1)
        self._kv.hincrby(self._key, "llm_input_tokens", input_tokens)
        self._kv.hincrby(self._key, "llm_output_tokens", output_tokens)

    def report_video_call(self, calls: int = 1) -> None:
        self._kv.hincrby(self._key, "video_calls", calls)

    def check_budget(self) -> None:
        """调用前硬闸：llm_calls + video_calls 超限抛 BudgetExceeded（Skill 决定降级/失败）。"""
        usage = self._kv.hgetall(self._key)
        calls = int(usage.get("llm_calls", 0)) + int(usage.get("video_calls", 0))
        if calls >= self._limit:
            raise BudgetExceeded(
                f"model call limit reached: {calls}/{self._limit}")


def kv_from_env() -> KvPort:
    """Job 内装配：REDIS_URL 直连；缺失时进程内降级（进度/usage 丢失可接受）。"""
    url = os.environ.get("REDIS_URL")
    if not url:
        return InMemoryKv()
    try:
        from ..adapters.kv import RedisKv

        return RedisKv(url)
    except Exception:  # noqa: BLE001 - Redis 故障降级为进程内（少收口径）
        return InMemoryKv()


def reporter_from_env() -> UsageReporter:
    task_id = os.environ.get("SKILL_TASK_ID", "unknown")
    limit = int(os.environ.get("TASK_MODEL_CALL_LIMIT", "500"))
    return UsageReporter(kv_from_env(), task_id, limit)
