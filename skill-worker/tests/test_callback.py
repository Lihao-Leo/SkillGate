"""回调通道测试（§5.5 / TC-CHN-001~003）：HMAC 签名、退避重试、死信。"""

from __future__ import annotations

import json

from skill_worker.adapters.crypto import decrypt, encrypt
from skill_worker.domain import RunResult, Usage
from skill_worker.scheduler.callback import callback_body, sign, verify
from tests.fakes import Harness

CRYPTO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="


def _armed_harness(callback_url="https://cb.example.com/hook") -> Harness:
    h = Harness()
    h.seed_execution(task_id="task_cb", callback_url=callback_url,
                     status="SUCCEEDED")
    cipher = encrypt(CRYPTO_KEY, "sk-secret-caller")
    h.db.execute("INSERT INTO app_key (app_key_id, tenant_id, secret_cipher) "
                 "VALUES ('sk-test', 'tenant-a', ?)", (cipher,))
    return h


def test_signature_roundtrip_and_tamper_detection():
    body = '{"taskId":"t1"}'
    timestamp = "1700000000000"
    signature = sign("sk-secret", timestamp, body)
    assert verify("sk-secret", timestamp, body, signature)
    assert not verify("sk-secret", timestamp, body + "x", signature)   # 篡改 body
    assert not verify("other", timestamp, body, signature)             # 错误密钥
    # 时间戳容差 5min（防重放）
    assert verify("sk-secret", timestamp, body, signature,
                  now_millis=int(timestamp) + 4 * 60 * 1000)
    assert not verify("sk-secret", timestamp, body, signature,
                      now_millis=int(timestamp) + 6 * 60 * 1000)


def test_callback_worker_success_marks_sent():
    h = _armed_harness()
    body = callback_body(h.executions.get("task_cb"), RunResult.success(),
                         [], 20, True, Usage())

    h.callback_worker.handle({"taskId": "task_cb", "body": body, "attempt": 1})

    assert h.execution_row("task_cb")["callback_status"] == "SENT"
    assert h.mq.messages_of("skill-callback-retry") == []
    assert h.mq.messages_of("skill-callback-dlq") == []
    # 投递带签名头
    request = h.callback_worker._http.requests[0]
    assert set(request["headers"]) >= {"X-Skill-Signature", "X-Skill-Timestamp"}
    assert verify("sk-secret-caller", request["headers"]["X-Skill-Timestamp"],
                  request["body"], request["headers"]["X-Skill-Signature"])


def test_callback_retry_backoff_then_dead_letter():
    h = _armed_harness()
    h.callback_worker._http.status = 500  # 持续失败
    body = {"taskId": "task_cb", "status": "SUCCEEDED"}

    h.callback_worker.handle({"taskId": "task_cb", "body": body, "attempt": 1})
    assert h.execution_row("task_cb")["callback_status"] == "RETRYING"
    retries = h.mq.messages_of("skill-callback-retry")
    assert retries[-1] == {"taskId": "task_cb", "body": body, "attempt": 2}
    assert h.mq.sent[-1].delay_level == 5  # 1min 延迟等级

    h.callback_worker.handle({"taskId": "task_cb", "body": body, "attempt": 2})
    assert h.mq.sent[-1].delay_level == 9  # 5min

    h.callback_worker.handle({"taskId": "task_cb", "body": body, "attempt": 3})
    assert h.execution_row("task_cb")["callback_status"] == "DEAD_LETTER"
    assert h.mq.messages_of("skill-callback-dlq") == [
        {"taskId": "task_cb", "attempt": 3, "callbackUrl": "https://cb.example.com/hook"}]


def test_missing_secret_goes_straight_to_dead_letter():
    h = _armed_harness()
    # 不种 app_key（无凭据）
    h.db.execute("DELETE FROM app_key")
    h.callback_worker.handle({"taskId": "task_cb", "body": {"taskId": "task_cb"}, "attempt": 1})
    assert h.execution_row("task_cb")["callback_status"] == "DEAD_LETTER"
    assert h.mq.messages_of("skill-callback-dlq")


def test_callback_body_isomorphic_fields():
    h = _armed_harness()
    execution = h.executions.get("task_cb")
    body = callback_body(execution, RunResult.timeout(600),
                         [{"type": "video", "url": "https://oss/v.mp4", "sizeBytes": 15}],
                         0, True, Usage(llm_calls=1), now=None)
    assert body["status"] == "FAILED"
    assert body["error"] == {"code": "TIMEOUT", "message": "Skill execution exceeded 600s"}
    assert body["billing"]["pointsCharged"] == 0
    assert body["timestamp"].endswith("Z")
    assert json.dumps(body)  # 可序列化
