"""飞书群告警通知器（自定义机器人 webhook）：可选启用，未配置即 no-op。

协议：POST {"timestamp","sign","msg_type":"text","content":{"text"}}；
配置了 FEISHU_SECRET 时启用加签（sign = base64(hmac_sha256(f"{ts}\\n{secret}", ""))）。
告警定位与事件链一致：发送失败仅记日志，绝不影响执行/看门狗主流程。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import time
import urllib.request

logger = logging.getLogger(__name__)


class FeishuNotifier:
    """飞书自定义机器人文本通知。webhook_url 为空时 enabled=False（no-op）。"""

    def __init__(self, webhook_url: str = "", secret: str = "", timeout_seconds: float = 5.0,
                 post_fn=None):
        self._url = (webhook_url or "").strip()
        self._secret = (secret or "").strip()
        self._timeout = timeout_seconds
        self._post = post_fn if post_fn is not None else self._post_urllib

    @property
    def enabled(self) -> bool:
        return bool(self._url)

    def send_text(self, text: str) -> bool:
        """发送文本消息；未启用/发送失败返回 False（仅记日志，不抛出）。"""
        if not self.enabled:
            return False
        payload: dict = {"msg_type": "text", "content": {"text": text[:4000]}}
        if self._secret:
            timestamp = str(int(time.time()))
            payload["timestamp"] = timestamp
            payload["sign"] = self._sign(timestamp, self._secret)
        try:
            self._post(self._url, payload, self._timeout)
            return True
        except Exception as error:  # noqa: BLE001 - 告警通道故障不影响主流程
            logger.warning("feishu notify failed: %s", error)
            return False

    def _post_urllib(self, url: str, payload: dict, timeout: float) -> None:
        body = json.dumps(payload).encode()
        request = urllib.request.Request(url, data=body, method="POST",
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status >= 300:
                raise RuntimeError(f"feishu http {response.status}")

    @staticmethod
    def _sign(timestamp: str, secret: str) -> str:
        string_to_sign = f"{timestamp}\n{secret}"
        digest = hmac.new(string_to_sign.encode(), b"", hashlib.sha256).digest()
        return base64.b64encode(digest).decode()
