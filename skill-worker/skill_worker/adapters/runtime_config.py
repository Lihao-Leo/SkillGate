"""运行时告警配置客户端（统一维护入口 = 管理后台系统配置页 sys_config）。

从 gateway 拉取告警配置（60s 缓存），失败保留上次值；gateway 不可达时
回退 worker 本地 env（Settings）——两级兜底，配置变更约 90s 内热生效
（gateway sys_config 缓存 30s + worker 拉取缓存 60s），无需重启。
"""

from __future__ import annotations

import json
import logging
import time
import urllib.request

from ..config import Settings

logger = logging.getLogger(__name__)


class AlertConfigClient:
    """GET /internal/runtime-config/alerts（X-Internal-Token），配置热更新来源。"""

    def __init__(self, gateway_base_url: str, internal_token: str, settings: Settings,
                 cache_seconds: float = 60.0, fetch_fn=None):
        self._url = gateway_base_url.rstrip("/") + "/internal/runtime-config/alerts"
        self._token = internal_token
        self._fallback_url = settings.feishu_webhook_url
        self._fallback_secret = settings.feishu_secret
        self._fallback_running = settings.alert_running_seconds
        self._fallback_tokens = settings.alert_tokens
        self._cache_seconds = cache_seconds
        self._fetch = fetch_fn if fetch_fn is not None else self._fetch_urllib
        self._cached: dict | None = None
        self._cached_at = 0.0

    # ------------------------------------------------------------------
    # 对外读取（告警路径调用）
    # ------------------------------------------------------------------

    def webhook_url(self) -> str:
        return self._value("webhookUrl", self._fallback_url)

    def secret(self) -> str:
        return self._value("secret", self._fallback_secret)

    def running_seconds(self) -> int:
        return self._int_value("runningSeconds", self._fallback_running)

    def tokens_threshold(self) -> int:
        return self._int_value("tokensThreshold", self._fallback_tokens)

    def notifier_ready(self) -> bool:
        """是否有可用 webhook（远端或 env 兜底），供调用方判断 no-op。"""
        return bool(self.webhook_url())

    def refresh_into(self, notifier) -> None:
        """把最新配置应用到通知器（webhook/secret）；空值不覆盖已有值。"""
        notifier.configure(self.webhook_url(), self.secret())

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    def _load(self) -> dict:
        now = time.time()
        if self._cached is None or now - self._cached_at > self._cache_seconds:
            try:
                self._cached = self._fetch()
                self._cached_at = now
            except Exception as error:  # noqa: BLE001 - 拉取失败保留上次值/env 兜底
                if self._cached is None:
                    logger.warning("alert config fetch failed, use env fallback: %s", error)
                else:
                    logger.warning("alert config fetch failed, keep last values: %s", error)
                if self._cached is None:
                    self._cached = {}
                self._cached_at = now
        return self._cached

    def _value(self, key: str, fallback: str) -> str:
        value = self._load().get(key)
        return str(value).strip() if value else fallback

    def _int_value(self, key: str, fallback: int) -> int:
        value = self._load().get(key)
        if value in (None, ""):
            return fallback
        try:
            return int(value)
        except (TypeError, ValueError):
            return fallback

    def _fetch_urllib(self) -> dict:
        request = urllib.request.Request(
            self._url, headers={"X-Internal-Token": self._token})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode())
