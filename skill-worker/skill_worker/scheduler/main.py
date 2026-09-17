"""scheduler 入口：装配生产适配器并启动（PushConsumer 回调驱动 + 看门狗定时）。

本地/测试不启动本入口——经 tests/fakes.py 装配进程内替身。
"""

from __future__ import annotations

import json
import logging
import time

from ..adapters.billing import HttpBillingClient
from ..adapters.db import PyMySqlDatabase
from ..adapters.k8s import K8sSandbox, LocalSubprocessSandbox
from ..adapters.kv import RedisKv
from ..adapters.mq import RocketMqAdapter
from ..adapters.oss import S3Oss
from ..adapters.repositories import (AppKeyRepository, ArtifactRepository, ExecutionRepository,
                                     SkillRepository)
from ..config import Settings, load_settings
from .callback import CallbackWorker, UrllibHttpPort
from .orchestrator import Orchestrator
from .watchdog import Watchdog

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("skill-worker")


def build(settings: Settings) -> tuple[Orchestrator, Watchdog, CallbackWorker]:
    db = PyMySqlDatabase(settings.mysql_host, settings.mysql_port, settings.mysql_db,
                         settings.mysql_user, settings.mysql_password)
    kv = RedisKv(settings.redis_url)
    oss = S3Oss(settings.oss_endpoint, settings.oss_region, settings.oss_bucket,
                settings.oss_access_key, settings.oss_secret_key,
                settings.oss_key_prefix)
    if settings.sandbox_mode == "k8s":
        sandbox = K8sSandbox(settings.k8s_namespace, settings.workspace_root,
                             pvc_name="skill-workspace")
    else:
        logger.info("sandbox mode: local subprocess（agent_runner=%s）",
                    settings.agent_runner_path)
        sandbox = LocalSubprocessSandbox(settings.workspace_root,
                                         settings.agent_runner_path)
    billing = HttpBillingClient(settings.billing_base_url, settings.billing_internal_token,
                                 settings.settle_timeout_seconds)
    mq = RocketMqAdapter(settings.mq_proxy_endpoint)

    executions = ExecutionRepository(db)
    orchestrator = Orchestrator(
        executions, ArtifactRepository(db), SkillRepository(db), sandbox, oss, kv, mq,
        billing, settings)
    watchdog = Watchdog(orchestrator, sandbox, kv, mq, settings)
    callback_worker = CallbackWorker(
        UrllibHttpPort(), mq, executions, AppKeyRepository(db),
        settings.crypto_key, settings.callback_timeout_seconds,
        settings.callback_max_attempts, settings.callback_delay_levels)
    _register_mq_consumers(settings, orchestrator, callback_worker, mq)
    return orchestrator, watchdog, callback_worker


def _parse_execute(body: bytes) -> str:
    """skill-execute 消息体：gateway 发裸 taskId 字符串；兼容 JSON {"taskId": ...}。"""
    text = body.decode()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict) and "taskId" in obj:
            return str(obj["taskId"])
    except ValueError:
        pass
    return text.strip()


def _register_mq_consumers(settings: Settings, orchestrator: Orchestrator,
                           callback_worker: CallbackWorker, mq) -> None:
    """MQ 订阅（at-least-once）：skill-execute / skill-callback-retry。

    handler 异常不 ack——不可见超时后自动重投；幂等由条件更新 / hold 状态 / taskId 消化。
    """
    mq.subscribe(settings.topic_execute, "skill_scheduler_group",
                 lambda body: orchestrator.handle(_parse_execute(body)))
    mq.subscribe(settings.topic_callback_retry, "skill_scheduler_group",
                 lambda body: callback_worker.handle(json.loads(body)))


def run() -> None:
    settings = load_settings()
    _orchestrator, watchdog, _callback = build(settings)

    while True:
        time.sleep(settings.watchdog_interval_seconds)
        try:
            watchdog.reconcile()
        except Exception:  # noqa: BLE001 - 看门狗单轮失败不终止
            logger.exception("watchdog reconcile failed")


if __name__ == "__main__":
    run()
