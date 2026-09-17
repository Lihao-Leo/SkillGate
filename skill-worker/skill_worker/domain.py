"""领域模型：执行状态机、运行结果、产物文件（纯数据，无 IO）。"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Optional

# execution.status（与 gateway §6.3 对齐）
PENDING = "PENDING"
RUNNING = "RUNNING"
CANCELLING = "CANCELLING"
SUCCEEDED = "SUCCEEDED"
FAILED = "FAILED"
CANCELLED = "CANCELLED"
TERMINAL_STATUSES = {SUCCEEDED, FAILED, CANCELLED}

# callback_status
CB_PENDING = "PENDING"
CB_SENT = "SENT"
CB_RETRYING = "RETRYING"
CB_DEAD_LETTER = "DEAD_LETTER"
CB_NO_CALLBACK = "NO_CALLBACK"


def is_terminal(status: str) -> bool:
    return status in TERMINAL_STATUSES


@dataclass
class RunResult:
    """沙箱运行结果（exit 0 / 非零退出 / 超时 / 取消 / 空产物 / 超限）。"""

    ok: bool
    error_code: Optional[str] = None
    error_message: Optional[str] = None

    @staticmethod
    def success() -> "RunResult":
        return RunResult(ok=True)

    @staticmethod
    def failed(code: str, message: str = "") -> "RunResult":
        return RunResult(ok=False, error_code=code, error_message=message or code)

    @staticmethod
    def timeout(limit_seconds: int) -> "RunResult":
        return RunResult.failed("TIMEOUT", f"Skill execution exceeded {limit_seconds}s")

    @staticmethod
    def cancelled() -> "RunResult":
        return RunResult.failed("CANCELLED", "cancelled by caller during execution")


@dataclass
class ArtifactFile:
    """产物文件（§4.7 出口契约；manifest 优先，缺省按后缀推断 type）。"""

    filename: str
    content: bytes
    type: str = "other"
    meta: dict = field(default_factory=dict)

    @property
    def size(self) -> int:
        return len(self.content)


@dataclass
class Execution:
    """execution 行（worker 读写的字段子集；快照反序列化为 dict）。"""

    task_id: str
    app_key_id: str
    tenant_id: str
    skill_code: str
    skill_version: str
    package_sha256: str
    input_ref: Optional[str]
    context: Optional[str]
    expected_count: Optional[int]
    output_config_snapshot: dict
    pricing_snapshot: dict
    hold_id: Optional[str]
    status: str
    callback_url: Optional[str]
    callback_status: str
    model_calls: int = 0
    tokens_used: int = 0
    created_at: Optional[datetime] = None
    started_at: Optional[datetime] = None

    @property
    def timeout_seconds(self) -> int:
        value = self.output_config_snapshot.get("timeoutSeconds") or 600
        return int(min(max(value, 1), 3600))  # 全局硬顶 3600（§4.1）

    @property
    def pricing_mode(self) -> str:
        return self.pricing_snapshot.get("mode", "PER_EXECUTION")

    def context_json(self) -> Any:
        if not self.context:
            return None
        try:
            return json.loads(self.context)
        except ValueError:
            return None


@dataclass
class JobOutcome:
    """沙箱 Job 观测结果。"""

    status: str  # SUCCEEDED / FAILED / RUNNING / MISSING
    exceeded_deadline: bool = False


@dataclass
class SettleOutcome:
    """billing.settle 返回（points_charged 为实扣）。"""

    points_charged: int
    status: str
    already_settled: bool = False


@dataclass
class Usage:
    """单任务用量（Redis task:usage:{taskId} 分类计数的刷回形态，§4.5）。"""

    llm_calls: int = 0
    llm_input_tokens: int = 0
    llm_output_tokens: int = 0
    video_calls: int = 0

    @property
    def model_calls(self) -> int:
        return self.llm_calls + self.video_calls

    @property
    def tokens(self) -> int:
        return self.llm_input_tokens + self.llm_output_tokens

    @staticmethod
    def from_hash(data: dict) -> "Usage":
        def n(key: str) -> int:
            try:
                return int(data.get(key, 0))
            except (TypeError, ValueError):
                return 0

        return Usage(
            llm_calls=n("llm_calls"),
            llm_input_tokens=n("llm_input_tokens"),
            llm_output_tokens=n("llm_output_tokens"),
            video_calls=n("video_calls"),
        )
