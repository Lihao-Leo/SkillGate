"""usage 链路测试（§4.5）：分类计数、终态刷回、METERED 折算封顶、上限硬闸。"""

from __future__ import annotations

from skill_worker.adapters.kv import InMemoryKv
from skill_worker.domain import Usage
from skill_worker.scheduler.usage import flush_usage, metered_points, read_usage
from skill_worker.toolset.usage import BudgetExceeded, UsageReporter


def test_usage_roundtrip_from_redis_hash():
    kv = InMemoryKv()
    key = "task:usage:t1"
    kv.hincrby(key, "llm_calls", 2)
    kv.hincrby(key, "llm_input_tokens", 1200)
    kv.hincrby(key, "llm_output_tokens", 300)
    kv.hincrby(key, "video_calls", 1)

    usage = read_usage(kv, "t1")
    assert usage == Usage(llm_calls=2, llm_input_tokens=1200,
                          llm_output_tokens=300, video_calls=1)
    assert usage.model_calls == 3
    assert usage.tokens == 1500


def test_flush_usage_reads_and_expires():
    kv = InMemoryKv()
    kv.hincrby("task:usage:t2", "llm_calls", 1)
    usage = flush_usage(kv, "t2")
    assert usage.llm_calls == 1
    kv.tick(24 * 3600 + 1)  # 24h 后过期（Skill 被杀按最后已知值的窗口）
    assert read_usage(kv, "t2") == Usage()


def test_metered_points_cap():
    usage = Usage(video_calls=100)  # 100 × 10 = 1000 → 封顶 500
    assert metered_points(usage, 0.01, 0.02, 10, cap_points=500) == 500
    # 无封顶（cap=0）：按用量计
    assert metered_points(Usage(video_calls=3), 0.01, 0.02, 10, cap_points=0) == 30
    # token 折算：1k 输入 × 0.01 + 1k 输出 × 0.02 = 0.03 → 整数点数向下取整
    assert metered_points(Usage(llm_input_tokens=1000, llm_output_tokens=1000),
                          0.01, 0.02, 10, cap_points=0) == 0


def test_budget_hard_limit():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "t3", model_call_limit=3)
    for _ in range(3):
        reporter.report_llm(10, 5)
    with_assert = False
    try:
        reporter.check_budget()
    except BudgetExceeded as error:
        with_assert = True
        assert "3/3" in str(error)
    assert with_assert
