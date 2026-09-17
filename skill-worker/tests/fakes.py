"""测试装配：进程内替身（sqlite / InMemory KV/OSS/MQ / FakeSandbox / FakeBilling）。"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass, field

from skill_worker.adapters.billing import BillingError
from skill_worker.adapters.db import SqliteDatabase
from skill_worker.adapters.k8s import FakeSandbox
from skill_worker.adapters.kv import InMemoryKv
from skill_worker.adapters.mq import InMemoryMq
from skill_worker.adapters.oss import InMemoryOss
from skill_worker.adapters.repositories import (AppKeyRepository, ArtifactRepository,
                                                ExecutionRepository, SkillRepository)
from skill_worker.config import Settings
from skill_worker.scheduler.callback import CallbackWorker
from skill_worker.scheduler.orchestrator import Orchestrator
from skill_worker.scheduler.watchdog import Watchdog

SCHEMA = """
CREATE TABLE execution (
    task_id TEXT PRIMARY KEY, app_key_id TEXT NOT NULL, tenant_id TEXT NOT NULL,
    skill_code TEXT NOT NULL, skill_version TEXT NOT NULL, package_sha256 TEXT NOT NULL,
    input_ref TEXT, context TEXT, expected_count INTEGER,
    output_config_snapshot TEXT, pricing_snapshot TEXT, hold_id TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING', error_code TEXT, error_message TEXT,
    callback_url TEXT, callback_status TEXT NOT NULL DEFAULT 'PENDING',
    model_calls INTEGER NOT NULL DEFAULT 0, tokens_used INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER, created_at TEXT, started_at TEXT, finished_at TEXT
);
CREATE TABLE artifact (
    artifact_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, tenant_id TEXT NOT NULL,
    type TEXT NOT NULL, oss_key TEXT NOT NULL, file_size INTEGER NOT NULL DEFAULT 0,
    content_type TEXT, meta TEXT
);
CREATE TABLE skill (
    tenant_id TEXT NOT NULL, skill_code TEXT NOT NULL, visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    required_abilities TEXT
);
CREATE TABLE app_key (
    app_key_id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, secret_cipher TEXT NOT NULL
);
"""


@dataclass
class FakeBilling:
    """可编程结算：failures_before_success 控制前 N 次抛 BillingError。"""

    calls: list = field(default_factory=list)
    failures_before_success: int = 0
    _attempt: int = 0

    def settle(self, task_id: str, actual_points: int):
        from skill_worker.domain import SettleOutcome

        self.calls.append((task_id, actual_points))
        self._attempt += 1
        if self._attempt <= self.failures_before_success:
            raise BillingError(f"programmed failure #{self._attempt}")
        return SettleOutcome(points_charged=actual_points, status="SETTLED")


class Harness:
    def __init__(self, **settings_overrides):
        self.db = SqliteDatabase(sqlite3.connect(":memory:"))
        self.db._conn.executescript(SCHEMA)
        self.kv = InMemoryKv()
        self.oss = InMemoryOss()
        self.mq = InMemoryMq()
        self.sandbox = FakeSandbox()
        self.billing = FakeBilling()
        defaults = dict(watch_poll_seconds=0.0, default_timeout_seconds=60)
        defaults.update(settings_overrides)
        self.settings = Settings(**defaults)

        self.executions = ExecutionRepository(self.db)
        self.artifacts = ArtifactRepository(self.db)
        self.skills = SkillRepository(self.db)
        self.app_keys = AppKeyRepository(self.db)
        self.orchestrator = Orchestrator(
            self.executions, self.artifacts, self.skills, self.sandbox, self.oss, self.kv,
            self.mq, self.billing, self.settings)
        self.watchdog = Watchdog(self.orchestrator, self.sandbox, self.kv, self.mq,
                                 self.settings)
        self.callback_worker = CallbackWorker(
            _FakeHttp(), self.mq, self.executions, self.app_keys,
            self.settings.crypto_key, timeout_seconds=1.0,
            max_attempts=self.settings.callback_max_attempts,
            delay_levels=self.settings.callback_delay_levels)

    # ---------------- 种子数据 ----------------

    def seed_execution(self, **overrides) -> dict:
        task_id = overrides.pop("task_id", "task_test_0001")
        package = overrides.pop("package_bytes", b"zip-bytes")
        row = dict(
            task_id=task_id, app_key_id="sk-test", tenant_id="tenant-a",
            skill_code="demo-skill", skill_version="1.0.0",
            package_sha256=hashlib.sha256(package).hexdigest(),
            input_ref="tenant-a/inputs/%s.json" % task_id, context='{"session":"s1"}',
            expected_count=2, output_config_snapshot='{"countable":true,"defaultCount":1,"maxCount":5}',
            pricing_snapshot='{"mode":"PER_EXECUTION","points":10}', hold_id="hold_1",
            status="PENDING", callback_url=None, created_at=None, started_at=None)
        row.update(overrides)
        if row["input_ref"]:
            self.oss.put(row["input_ref"], json.dumps({"instructions": "demo"}).encode(),
                         "application/json")
        self.oss.put("skill/tenant-a/%s/%s/skill.zip" % (row["skill_code"], row["skill_version"]),
                     package)
        columns = ",".join(row.keys())
        placeholders = ",".join("?" for _ in row)
        self.db.execute(
            f"INSERT INTO execution ({columns}) VALUES ({placeholders})", tuple(row.values()))
        return row

    def seed_skill(self, skill_code="demo-skill", tenant_id="tenant-a",
                   required_abilities='["llm-text"]', visibility="PRIVATE"):
        self.db.execute(
            "INSERT INTO skill (tenant_id, skill_code, visibility, required_abilities) "
            "VALUES (?, ?, ?, ?)", (tenant_id, skill_code, visibility, required_abilities))

    def execution_row(self, task_id: str) -> dict:
        rows = self.db.query("SELECT * FROM execution WHERE task_id = ?", (task_id,))
        return rows[0] if rows else {}

    def artifact_count(self, task_id: str) -> int:
        return len(self.db.query("SELECT * FROM artifact WHERE task_id = ?", (task_id,)))


@dataclass
class _FakeHttp:
    """可编程 HTTP：status 控制回调成败。"""

    status: int = 200
    requests: list = field(default_factory=list)

    def post_json(self, url, body_json, headers, timeout_seconds):
        self.requests.append({"url": url, "body": body_json, "headers": headers})
        if self.status >= 500:
            raise IOError("connection refused (programmed)")
        return self.status
