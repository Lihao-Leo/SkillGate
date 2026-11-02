"""飞书通知器单元测试：加签协议、no-op、失败不抛出。"""

from __future__ import annotations

import json

from skill_worker.adapters.feishu import FeishuNotifier


def test_disabled_when_no_webhook():
    n = FeishuNotifier()
    assert n.enabled is False
    assert n.send_text("x") is False  # no-op 不发送


def test_posts_text_with_sign_when_secret_set():
    captured = {}

    def post(url, payload, timeout):
        captured["url"] = url
        captured["payload"] = payload

    n = FeishuNotifier(webhook_url="https://open.feishu.cn/hook/x", secret="s3cr3t", post_fn=post)
    assert n.enabled is True

    ok = n.send_text("任务失败 task=1")

    assert ok is True
    assert captured["url"] == "https://open.feishu.cn/hook/x"
    body = captured["payload"]
    assert body["msg_type"] == "text"
    assert body["content"]["text"] == "任务失败 task=1"
    # 加签字段：timestamp + sign（base64，非空）
    assert body["timestamp"].isdigit()
    assert len(body["sign"]) > 10


def test_long_text_truncated_to_4000():
    captured = {}

    def post(url, payload, timeout):
        captured["payload"] = payload

    n = FeishuNotifier(webhook_url="https://x", post_fn=post)
    n.send_text("长" * 6000)

    assert len(captured["payload"]["content"]["text"]) == 4000


def test_post_failure_returns_false_never_raises():
    def boom(url, payload, timeout):
        raise ConnectionError("network down")

    n = FeishuNotifier(webhook_url="https://x", post_fn=boom)
    assert n.send_text("x") is False


def test_sign_is_deterministic_base64():
    # 飞书加签算法：base64(hmac_sha256(key=f"{ts}\n{secret}", msg=""))
    sig1 = FeishuNotifier._sign("1700000000", "abc")
    sig2 = FeishuNotifier._sign("1700000000", "abc")
    assert sig1 == sig2
    import base64
    import hashlib
    import hmac
    expect = base64.b64encode(
        hmac.new(b"1700000000\nabc", b"", hashlib.sha256).digest()).decode()
    assert sig1 == expect
    _ = json  # json 保留导入供后续断言扩展
