"""EventReporter 单元测试（TC-TR-003 上报侧）：批量上报、网关不可达不阻塞不抛出、恢复续传、
队列溢出丢最旧、stop 时尽力清空。post_fn 注入模拟网关，无网络依赖。"""

from skill_worker.adapters.events import EventReporter


def make_reporter(post_fn, queue_size=2000):
    reporter = EventReporter(
        gateway_base_url="http://gateway:8888", internal_token="token-x",
        post_fn=post_fn, queue_size=queue_size)
    return reporter


def event(task_id="t1", stage="SANDBOX"):
    return {"taskId": task_id, "tenantId": "acme", "stage": stage, "status": "OK",
            "service": "worker", "occurredAt": "2026-10-31T18:00:00.000"}


def test_flush_posts_batch_and_returns_accepted():
    captured = {}

    def post(batch):
        captured["events"] = batch
        return 42

    reporter = make_reporter(post)
    for stage in ("CLAIM", "STAGE", "SANDBOX"):
        reporter.report(event(stage=stage))

    accepted = reporter.flush()

    assert accepted == 42
    assert [e["stage"] for e in captured["events"]] == ["CLAIM", "STAGE", "SANDBOX"]
    assert captured["events"][0]["taskId"] == "t1"


def test_unreachable_gateway_never_raises_and_drops_batch():
    def unreachable(batch):
        raise ConnectionError("gateway down")

    reporter = make_reporter(unreachable)
    reporter.report(event())

    # flush 吞掉连接异常返回 0；report/flush 对调用方零抛出、零阻塞
    assert reporter.flush() == 0


def test_recovers_after_gateway_returns():
    calls = {"n": 0}

    def flaky(batch):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ConnectionError("gateway down")
        return len(batch)

    reporter = make_reporter(flaky)
    reporter.report(event(stage="A"))
    assert reporter.flush() == 0  # 第一次失败丢弃

    reporter.report(event(stage="B"))
    assert reporter.flush() == 1  # 恢复后续传成功


def test_queue_overflow_drops_oldest():
    captured = {}

    def post(batch):
        captured["events"] = batch
        return len(batch)

    reporter = make_reporter(post, queue_size=2)
    reporter.report(event(stage="A"))
    reporter.report(event(stage="B"))
    reporter.report(event(stage="C"))  # 队列满：丢 A

    reporter.flush()

    assert [e["stage"] for e in captured["events"]] == ["B", "C"]


def test_single_post_per_flush_respects_max_batch():
    bodies = []

    def post(batch):
        bodies.append(batch)
        return len(batch)

    reporter = make_reporter(post)
    for i in range(5):
        reporter.report(event(stage=f"S{i}"))

    accepted = reporter.flush()

    assert accepted == 5
    assert len(bodies) == 1  # 一次 flush 一批


def test_stop_flushes_pending_best_effort():
    delivered = []

    def post(batch):
        delivered.extend(batch)
        return len(batch)

    reporter = make_reporter(post)
    reporter.report(event(stage="FINAL"))

    reporter.stop()

    assert [e["stage"] for e in delivered] == ["FINAL"]
