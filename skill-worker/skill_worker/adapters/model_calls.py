"""模型调用明细上报客户端（V2.1）：缓冲队列 + 后台线程批量 POST gateway /internal/model-calls。

观测定位与 events.py 同构：网关不可达时静默丢弃该批并计数——上报永不阻塞、永不抛出。
字段命名 camelCase，gateway 落库时按 alias 费率折算 cost_points。
"""

from __future__ import annotations

import json
import logging
import threading
import urllib.request
from collections import deque

logger = logging.getLogger(__name__)


class ModelCallReporter:
    """POST /internal/model-calls（X-Internal-Token），单批上限与服务端一致（200）。"""

    def __init__(self, gateway_base_url: str, internal_token: str,
                 flush_interval_seconds: float = 2.0, max_batch: int = 200,
                 queue_size: int = 5000, post_fn=None):
        self._url = gateway_base_url.rstrip("/") + "/internal/model-calls"
        self._token = internal_token
        self._flush_interval = flush_interval_seconds
        self._max_batch = max(1, min(max_batch, 200))
        self._pending: deque = deque(maxlen=queue_size)
        self._dropped = 0
        self._lock = threading.Lock()
        self._wakeup = threading.Event()
        self._closing = False
        self._post = post_fn if post_fn is not None else self._post_urllib
        self._thread = threading.Thread(target=self._loop, name="model-call-reporter", daemon=True)
        self._thread.start()

    def report(self, call: dict) -> None:
        """入队一条明细（camelCase 键）；队列满丢最旧并计数——永不阻塞调用线程。"""
        with self._lock:
            if len(self._pending) == self._pending.maxlen:
                self._pending.popleft()
                self._dropped += 1
            self._pending.append(call)

    def flush(self) -> int:
        """取一批上报一次；失败静默丢弃该批（计数告警）；返回 accepted 数。"""
        with self._lock:
            size = min(self._max_batch, len(self._pending))
            batch = [self._pending.popleft() for _ in range(size)]
            dropped, self._dropped = self._dropped, 0
        if not batch:
            return 0
        try:
            accepted = int(self._post(batch))
            if dropped:
                logger.warning("model call queue overflow: dropped %d oldest entries", dropped)
            return accepted
        except Exception as error:  # noqa: BLE001 - 网关不可达/旧版网关无此端点：丢弃该批
            logger.warning("model call flush failed, drop %d entries: %s", len(batch), error)
            return 0

    def stop(self, join_timeout: float = 3.0) -> None:
        """停止后台线程并尽力清空余量（best-effort，失败丢弃）。"""
        self._closing = True
        self._wakeup.set()
        self._thread.join(timeout=join_timeout)
        try:
            self.flush()
        except Exception as error:  # noqa: BLE001 - 关闭路径的最终兜底
            logger.warning("model call flush on stop failed: %s", error)

    def _loop(self) -> None:
        while not self._closing:
            self._wakeup.wait(self._flush_interval)
            self._wakeup.clear()
            if self._closing:
                break
            self.flush()
        self.flush()

    def _post_urllib(self, batch: list) -> int:
        body = json.dumps({"calls": batch}).encode()
        request = urllib.request.Request(
            self._url, data=body, method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": self._token})
        with urllib.request.urlopen(request, timeout=5) as response:
            envelope = json.loads(response.read().decode())
        return int(envelope.get("accepted", 0))
