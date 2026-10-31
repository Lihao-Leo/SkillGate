"""执行事件上报客户端（技术方案 v2.0 §1.4）：缓冲队列 + 后台线程批量 POST gateway internal 端点。

观测定位：网关不可达时静默丢弃该批并计数告警——上报永不阻塞、永不抛出到执行线程；
恢复后继续上报。字段命名与接口契约一致（camelCase）。
"""

from __future__ import annotations

import json
import logging
import threading
import urllib.request
from collections import deque

logger = logging.getLogger(__name__)


class EventReporter:
    """POST /internal/execution-events（X-Internal-Token），单批上限与服务端一致（100）。"""

    def __init__(self, gateway_base_url: str, internal_token: str,
                 flush_interval_seconds: float = 2.0, max_batch: int = 100,
                 queue_size: int = 2000, post_fn=None):
        self._url = gateway_base_url.rstrip("/") + "/internal/execution-events"
        self._token = internal_token
        self._flush_interval = flush_interval_seconds
        self._max_batch = max(1, min(max_batch, 100))
        self._pending: deque = deque(maxlen=queue_size)
        self._dropped = 0
        self._lock = threading.Lock()
        self._wakeup = threading.Event()
        self._closing = False
        self._post = post_fn if post_fn is not None else self._post_urllib
        self._thread = threading.Thread(target=self._loop, name="event-reporter", daemon=True)
        self._thread.start()

    def report(self, event: dict) -> None:
        """入队一条事件（camelCase 键）；队列满丢最旧并计数——永不阻塞调用线程。"""
        with self._lock:
            if len(self._pending) == self._pending.maxlen:
                self._pending.popleft()
                self._dropped += 1
            self._pending.append(event)

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
                logger.warning("event queue overflow: dropped %d oldest events", dropped)
            return accepted
        except Exception as error:  # noqa: BLE001 - 网关不可达：丢弃该批，不阻塞执行线程
            logger.warning("event flush failed, drop %d events: %s", len(batch), error)
            return 0

    def stop(self, join_timeout: float = 3.0) -> None:
        """停止后台线程并尽力清空余量（best-effort，失败丢弃）。"""
        self._closing = True
        self._wakeup.set()
        self._thread.join(timeout=join_timeout)
        try:
            self.flush()
        except Exception as error:  # noqa: BLE001 - 关闭路径的最终兜底
            logger.warning("event flush on stop failed: %s", error)

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    def _loop(self) -> None:
        while not self._closing:
            self._wakeup.wait(self._flush_interval)
            self._wakeup.clear()
            if self._closing:
                break
            self.flush()
        self.flush()  # 关闭前最后一次尽力清空

    def _post_urllib(self, batch: list) -> int:
        body = json.dumps({"events": batch}).encode()
        request = urllib.request.Request(
            self._url, data=body, method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": self._token})
        with urllib.request.urlopen(request, timeout=5) as response:
            envelope = json.loads(response.read().decode())
        return int(envelope.get("accepted", 0))
