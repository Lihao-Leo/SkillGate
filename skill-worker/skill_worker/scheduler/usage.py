"""usage 采集链路（§4.5）：Redis task:usage:{taskId} → 终态刷回 execution → EXPIRE 24h。"""

from __future__ import annotations

from ..adapters.kv import KvPort
from ..domain import Usage

USAGE_KEY = "task:usage:{task_id}"
USAGE_TTL_SECONDS = 24 * 3600


def read_usage(kv: KvPort, task_id: str) -> Usage:
    return Usage.from_hash(kv.hgetall(USAGE_KEY.format(task_id=task_id)))


def flush_usage(kv: KvPort, task_id: str) -> Usage:
    """终态读取并置 24h 过期（Skill 被杀时按最后已知值结算的兜底口径）。"""
    usage = read_usage(kv, task_id)
    kv.expire(USAGE_KEY.format(task_id=task_id), USAGE_TTL_SECONDS)
    return usage


def metered_points(usage: Usage, cost_per_1k_input: float, cost_per_1k_output: float,
                    cost_per_video_call: float, cap_points: int) -> int:
    """METERED 用量折算（点数），封顶 capPoints。"""
    points = (
        usage.llm_input_tokens / 1000.0 * cost_per_1k_input
        + usage.llm_output_tokens / 1000.0 * cost_per_1k_output
        + usage.video_calls * cost_per_video_call
    )
    return int(min(points, cap_points)) if cap_points > 0 else int(points)
