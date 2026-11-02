"""AlertConfigClient 单元测试：远端配置读取、缓存、失败回退 env、refresh_into 热下发。"""

from __future__ import annotations

from skill_worker.adapters.feishu import FeishuNotifier
from skill_worker.adapters.runtime_config import AlertConfigClient
from skill_worker.config import Settings


def make_client(fetch_fn, settings=None):
    settings = settings or Settings()
    return AlertConfigClient("http://gateway:8888", "tok", settings, fetch_fn=fetch_fn)


def test_reads_remote_config():
    calls = []

    def fetch():
        calls.append(1)
        return {"webhookUrl": "https://remote/hook", "secret": "remote-secret",
                "runningSeconds": 120, "tokensThreshold": 5000}

    client = make_client(fetch)
    assert client.webhook_url() == "https://remote/hook"
    assert client.secret() == "remote-secret"
    assert client.running_seconds() == 120
    assert client.tokens_threshold() == 5000
    assert len(calls) == 1  # 缓存内不再拉取


def test_cache_within_window_no_refetch():
    calls = []

    def fetch():
        calls.append(1)
        return {"webhookUrl": "https://remote/hook"}

    client = make_client(fetch)
    client.webhook_url()
    client.webhook_url()
    client.running_seconds()

    assert len(calls) == 1


def test_fetch_failure_falls_back_to_env():
    settings = Settings(feishu_webhook_url="https://env/hook", alert_running_seconds=333)

    def boom():
        raise ConnectionError("gateway down")

    client = make_client(boom, settings)
    assert client.webhook_url() == "https://env/hook"
    assert client.running_seconds() == 333
    assert client.tokens_threshold() == 0  # settings 默认


def test_notifier_ready_reflects_remote_and_env():
    client = make_client(lambda: {"webhookUrl": ""})
    assert client.notifier_ready() is False  # 远端空 + env 兜底空

    client2 = make_client(lambda: {"webhookUrl": "https://remote/hook"})
    assert client2.notifier_ready() is True


def test_refresh_into_updates_notifier():
    client = make_client(lambda: {"webhookUrl": "https://remote/hook", "secret": "rs"})
    notifier = FeishuNotifier()  # 初始 no-op

    client.refresh_into(notifier)

    assert notifier.enabled is True


def test_empty_remote_values_do_not_override_env():
    settings = Settings(feishu_webhook_url="https://env/hook")
    client = make_client(lambda: {"webhookUrl": "", "secret": ""}, settings)

    assert client.webhook_url() == "https://env/hook"  # 远端空 → env 兜底
    assert client.secret() == ""  # 远端空且 env 空 → 空
