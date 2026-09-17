"""仓储层：execution / artifact / skill / app_key 读写，条件更新承载状态机不变量。"""

from __future__ import annotations

import json
from datetime import datetime
from typing import Optional

from ..domain import Execution
from .db import Database

_EXECUTION_COLUMNS = (
    "task_id, app_key_id, tenant_id, skill_code, skill_version, package_sha256, "
    "input_ref, context, expected_count, output_config_snapshot, pricing_snapshot, "
    "hold_id, status, callback_url, callback_status, model_calls, tokens_used, "
    "created_at, started_at"
)


def _parse_dt(value) -> Optional[datetime]:
    """时间列归一化：pymysql 返回 datetime，sqlite 返回字符串。"""
    if isinstance(value, datetime) or value is None:
        return value
    try:
        return datetime.fromisoformat(str(value).replace("T", " ").split(".")[0])
    except ValueError:
        return None


def _row_to_execution(row: dict) -> Execution:
    def parse_json(text, default):
        if not text:
            return default
        try:
            return json.loads(text)
        except (TypeError, ValueError):
            return default

    return Execution(
        task_id=row["task_id"],
        app_key_id=row["app_key_id"],
        tenant_id=row["tenant_id"],
        skill_code=row["skill_code"],
        skill_version=row["skill_version"],
        package_sha256=row["package_sha256"],
        input_ref=row.get("input_ref"),
        context=row.get("context"),
        expected_count=row.get("expected_count"),
        output_config_snapshot=parse_json(row.get("output_config_snapshot"), {}),
        pricing_snapshot=parse_json(row.get("pricing_snapshot"), {}),
        hold_id=row.get("hold_id"),
        status=row["status"],
        callback_url=row.get("callback_url"),
        callback_status=row.get("callback_status") or "PENDING",
        model_calls=row.get("model_calls") or 0,
        tokens_used=row.get("tokens_used") or 0,
        created_at=_parse_dt(row.get("created_at")),
        started_at=_parse_dt(row.get("started_at")),
    )


class ExecutionRepository:
    def __init__(self, db: Database):
        self._db = db

    def get(self, task_id: str) -> Optional[Execution]:
        rows = self._db.query(
            f"SELECT {_EXECUTION_COLUMNS} FROM execution WHERE task_id = ?", (task_id,))
        return _row_to_execution(rows[0]) if rows else None

    def status(self, task_id: str) -> Optional[str]:
        rows = self._db.query("SELECT status FROM execution WHERE task_id = ?", (task_id,))
        return rows[0]["status"] if rows else None

    def claim_running(self, task_id: str, now: datetime) -> int:
        """接手条件更新：仅 PENDING 可转 RUNNING（§4.10 防双消费）"""
        return self._db.execute(
            "UPDATE execution SET status = 'RUNNING', started_at = ? "
            "WHERE task_id = ? AND status = 'PENDING'", (now, task_id))

    def rollback_to_pending(self, task_id: str) -> int:
        """RUNNING 孤儿回滚（Job 连续 2 周期 missing）：条件回滚，不变量接管"""
        return self._db.execute(
            "UPDATE execution SET status = 'PENDING', started_at = NULL "
            "WHERE task_id = ? AND status = 'RUNNING'", (task_id,))

    def mark_cancelling_handled(self, task_id: str) -> int:
        """watchdog 已收殓的 CANCELLING → CANCELLED（置终态前结算已完成）"""
        return self._db.execute(
            "UPDATE execution SET status = 'CANCELLED', error_code = 'CANCELLED', "
            "finished_at = ? WHERE task_id = ? AND status = 'CANCELLING'", (datetime.now(), task_id))

    def finalize(self, task_id: str, status: str, error_code: Optional[str],
                 error_message: Optional[str], duration_ms: Optional[int],
                 model_calls: int, tokens_used: int, now: datetime,
                 callback_status: Optional[str] = None) -> int:
        """置终态（最后一步；条件更新保证只从 RUNNING/CANCELLING 进入）"""
        return self._db.execute(
            "UPDATE execution SET status = ?, error_code = ?, error_message = ?, "
            "duration_ms = ?, model_calls = ?, tokens_used = ?, finished_at = ?, "
            "callback_status = COALESCE(?, callback_status) "
            "WHERE task_id = ? AND status IN ('RUNNING', 'CANCELLING')",
            (status, error_code, error_message, duration_ms, model_calls, tokens_used,
             now, callback_status, task_id))

    def finalize_pending_failed(self, task_id: str) -> int:
        """PENDING 看门狗 30min 兜底：仅 PENDING 可置 FAILED(INTERNAL)（§4.10）"""
        return self._db.execute(
            "UPDATE execution SET status = 'FAILED', error_code = 'INTERNAL', "
            "error_message = 'pending watchdog: never claimed over 30min', "
            "finished_at = ?, callback_status = CASE WHEN callback_url IS NULL "
            "THEN 'NO_CALLBACK' ELSE callback_status END "
            "WHERE task_id = ? AND status = 'PENDING'", (datetime.now(), task_id))

    def update_progress(self, task_id: str, progress: int) -> int:
        """RUNNING 期间把技能上报进度落库（gateway 查询展示，§5.4）"""
        return self._db.execute(
            "UPDATE execution SET progress = ? WHERE task_id = ? AND status = 'RUNNING'",
            (progress, task_id))

    def update_usage(self, task_id: str, model_calls: int, tokens_used: int) -> int:
        return self._db.execute(
            "UPDATE execution SET model_calls = ?, tokens_used = ? WHERE task_id = ?",
            (model_calls, tokens_used, task_id))

    def set_callback_status(self, task_id: str, callback_status: str) -> int:
        return self._db.execute(
            "UPDATE execution SET callback_status = ? WHERE task_id = ?",
            (callback_status, task_id))

    def pending_before(self, cutoff: datetime) -> list[Execution]:
        rows = self._db.query(
            f"SELECT {_EXECUTION_COLUMNS} FROM execution "
            "WHERE status = 'PENDING' AND created_at < ? ORDER BY created_at LIMIT 100",
            (cutoff,))
        return [_row_to_execution(row) for row in rows]

    def running(self) -> list[Execution]:
        rows = self._db.query(
            f"SELECT {_EXECUTION_COLUMNS} FROM execution "
            "WHERE status = 'RUNNING' ORDER BY started_at LIMIT 100", ())
        return [_row_to_execution(row) for row in rows]

    def cancelling(self) -> list[Execution]:
        rows = self._db.query(
            f"SELECT {_EXECUTION_COLUMNS} FROM execution "
            "WHERE status = 'CANCELLING' ORDER BY created_at LIMIT 100", ())
        return [_row_to_execution(row) for row in rows]


class ArtifactRepository:
    def __init__(self, db: Database):
        self._db = db

    def exists(self, task_id: str) -> bool:
        rows = self._db.query(
            "SELECT COUNT(*) AS n FROM artifact WHERE task_id = ?", (task_id,))
        return bool(rows and rows[0]["n"])

    def insert(self, task_id: str, tenant_id: str, files: list) -> None:
        from ..common import new_id

        from ..domain import ArtifactFile

        for file in files:  # type: ArtifactFile
            self._db.execute(
                "INSERT INTO artifact (artifact_id, task_id, tenant_id, type, oss_key, "
                "file_size, content_type, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (new_id("art_"), task_id, tenant_id, file.type,
                 f"{tenant_id}/artifacts/{task_id}/{file.filename}",
                 file.size, "application/octet-stream",
                 json.dumps(file.meta) if file.meta else None))


class SkillRepository:
    def __init__(self, db: Database):
        self._db = db

    def _find(self, skill_code: str, caller_tenant_id: str) -> Optional[dict]:
        # 本租户行绝对优先（同 skillCode 可能同时存在他租户 PUBLIC 行）
        rows = self._db.query(
            "SELECT tenant_id, required_abilities FROM skill "
            "WHERE skill_code = ? AND (tenant_id = ? OR visibility = 'PUBLIC') "
            "ORDER BY (tenant_id = ?) DESC LIMIT 1",
            (skill_code, caller_tenant_id, caller_tenant_id))
        return rows[0] if rows else None

    def skill_tenant(self, skill_code: str, caller_tenant_id: str) -> Optional[str]:
        """Skill 归属租户（包 oss_key 前缀；公开 Skill 可能源于他租户）"""
        row = self._find(skill_code, caller_tenant_id)
        return row.get("tenant_id") if row else None

    def required_abilities(self, skill_code: str, caller_tenant_id: str) -> list[str]:
        row = self._find(skill_code, caller_tenant_id)
        if not row or not row.get("required_abilities"):
            return []
        try:
            value = json.loads(row["required_abilities"])
            return value if isinstance(value, list) else []
        except (TypeError, ValueError):
            return []


class AppKeyRepository:
    def __init__(self, db: Database):
        self._db = db

    def secret_cipher(self, app_key_id: str) -> Optional[str]:
        rows = self._db.query(
            "SELECT secret_cipher FROM app_key WHERE app_key_id = ?", (app_key_id,))
        return rows[0]["secret_cipher"] if rows else None
