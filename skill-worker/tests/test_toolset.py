"""toolset 测试（§4.5/§4.7）：契约封装、video_gen 回退与进度、LLM usage 上报。"""

from __future__ import annotations

import json

import pytest

from skill_worker.adapters.kv import InMemoryKv
from skill_worker.toolset import load_input, report_progress, save_artifact
from skill_worker.toolset.llm import LlmClient
from skill_worker.toolset.usage import BudgetExceeded, UsageReporter
from skill_worker.toolset.video_gen import VideoGen, VideoGenError, VideoGenTimeout


class FakeProvider:
    def __init__(self, alias, fail_submit=False, fail_poll=False, polls_before_done=0):
        self._alias = alias
        self._fail_submit = fail_submit
        self._fail_poll = fail_poll
        self._polls = polls_before_done
        self.submitted = []
        self.cancelled = []

    def alias(self) -> str:
        return self._alias

    def submit(self, prompt, options):
        if self._fail_submit:
            raise RuntimeError("provider quota exceeded")
        self.submitted.append(prompt)
        return f"{self._alias}-task-1"

    def poll(self, provider_task_id):
        if self._fail_poll:
            return {"status": "FAILED", "error": "render crash"}
        if self._polls > 0:
            self._polls -= 1
            return {"status": "RUNNING"}
        return {"status": "SUCCEEDED", "url": f"https://provider/{provider_task_id}.mp4"}

    def cancel(self, provider_task_id):
        self.cancelled.append(provider_task_id)


# ------------------------------------------------------------------
# 契约封装（§4.7）
# ------------------------------------------------------------------

def test_load_input_and_save_artifact_contract(tmp_path, monkeypatch):
    input_path = tmp_path / "input.json"
    input_path.write_text(json.dumps(
        {"materials": [{"type": "video", "url": "https://oss/presigned.mp4"}],
         "instructions": "生成 vlog", "context": {"s": 1}, "count": 2}), encoding="utf-8")
    monkeypatch.setenv("SKILL_INPUT", str(input_path))
    monkeypatch.setenv("SKILL_TASK_ID", "task_toolset")

    data = load_input()
    assert data["count"] == 2
    assert data["materials"][0]["type"] == "video"

    save_artifact("拆解.md", "# 结果", type="document", meta={"pages": 1})
    save_artifact("out.json", {"a": 1})

    artifacts = tmp_path / "artifacts"
    manifest = json.loads((artifacts / "manifest.json").read_text(encoding="utf-8"))
    names = {entry["filename"] for entry in manifest["files"]}
    assert names == {"拆解.md", "out.json"}
    assert (artifacts / "拆解.md").read_text(encoding="utf-8") == "# 结果"

    # 同名覆盖：manifest 不产生重复条目
    save_artifact("out.json", {"a": 2})
    manifest = json.loads((artifacts / "manifest.json").read_text(encoding="utf-8"))
    assert len([e for e in manifest["files"] if e["filename"] == "out.json"]) == 1


def test_report_progress_writes_kv(monkeypatch):
    kv = InMemoryKv()
    monkeypatch.setenv("SKILL_TASK_ID", "task_p")
    monkeypatch.setenv("REDIS_URL", "")  # 降级进程内
    # sdk 模块持有了 kv_from_env 的直接引用，须在 sdk 命名空间打补丁
    monkeypatch.setattr("skill_worker.toolset.sdk.kv_from_env", lambda: kv)
    report_progress(37)
    assert kv.get("task:progress:task_p") == "37"
    report_progress(150)  # 钳制 0-100
    assert kv.get("task:progress:task_p") == "100"


# ------------------------------------------------------------------
# video_gen（§4.5：提交/轮询/取结果/回退/进度）
# ------------------------------------------------------------------

def test_video_gen_success_with_usage_and_progress():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "task_v", model_call_limit=10)
    progress_log = []
    provider = FakeProvider("primary", polls_before_done=2)
    gen = VideoGen([provider], reporter, poll_interval_seconds=0.0,
                   progress=progress_log.append)

    url = gen.generate("一只猫的 vlog", {"timeout": 30})

    assert url.endswith(".mp4")
    assert kv.hgetall("task:usage:task_v")["video_calls"] == "1"
    assert progress_log[0] == 10  # 提交=10%
    assert progress_log[-1] == 95  # 取回=95%


def test_video_gen_falls_back_to_secondary_provider():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "task_v2", model_call_limit=10)
    primary = FakeProvider("primary", fail_submit=True)
    secondary = FakeProvider("secondary")

    gen = VideoGen([primary, secondary], reporter, poll_interval_seconds=0.0)
    url = gen.generate("prompt")

    assert "secondary" in url
    assert secondary.submitted == ["prompt"]


def test_video_gen_all_providers_failed():
    reporter = UsageReporter(InMemoryKv(), "t", 10)
    gen = VideoGen(
        [FakeProvider("a", fail_poll=True), FakeProvider("b", fail_submit=True)],
        reporter, poll_interval_seconds=0.0)
    with pytest.raises(VideoGenError) as error:
        gen.generate("prompt")
    assert "a" in str(error.value) and "b" in str(error.value)


def test_video_gen_timeout_raises():
    reporter = UsageReporter(InMemoryKv(), "t", 10)

    class NeverDone:
        def alias(self) -> str:
            return "slow"

        def submit(self, prompt, options):
            return "slow-1"

        def poll(self, provider_task_id):
            return {"status": "RUNNING"}

        def cancel(self, provider_task_id):
            pass

    gen = VideoGen([NeverDone()], reporter, poll_interval_seconds=0.0, max_poll_seconds=0)
    with pytest.raises(VideoGenTimeout):
        gen.generate("prompt", {"timeout": 0})


def test_video_gen_budget_gate():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "t", model_call_limit=1)
    reporter.report_llm(1, 1)  # 已达上限
    gen = VideoGen([FakeProvider("p")], reporter, poll_interval_seconds=0.0)
    with pytest.raises(BudgetExceeded):
        gen.generate("prompt")


# ------------------------------------------------------------------
# LLM 薄封装（usage 上报 + 上限硬闸）
# ------------------------------------------------------------------

class FakeLlmHttp:
    def __init__(self):
        self.calls = []

    def post_json(self, url, body_json, headers, timeout_seconds):
        self.calls.append({"url": url, "body": json.loads(body_json), "headers": headers})
        return 200, {
            "choices": [{"message": {"content": "好的"}}],
            "usage": {"prompt_tokens": 120, "completion_tokens": 30},
        }


def test_llm_chat_reports_usage_and_task_header():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "task_llm", model_call_limit=10)
    http = FakeLlmHttp()
    client = LlmClient(http, reporter, base_url="http://litellm.test",
                       api_key="sk-platform-task-x", task_id="task_llm")

    answer = client.chat([{"role": "user", "content": "hi"}], model="llm-text")

    assert answer == "好的"
    usage = kv.hgetall("task:usage:task_llm")
    assert usage["llm_calls"] == "1"
    assert usage["llm_input_tokens"] == "120"
    assert usage["llm_output_tokens"] == "30"
    assert http.calls[0]["headers"]["X-Task-Id"] == "task_llm"


def test_llm_chat_budget_exceeded_before_call():
    kv = InMemoryKv()
    reporter = UsageReporter(kv, "task_llm", model_call_limit=1)
    reporter.report_llm(1, 1)
    client = LlmClient(FakeLlmHttp(), reporter, base_url="http://litellm.test")
    with pytest.raises(BudgetExceeded):
        client.chat([{"role": "user", "content": "hi"}])


def test_llm_chat_error_raises():
    class ErrorHttp:
        def post_json(self, url, body_json, headers, timeout_seconds):
            return 500, {"error": "upstream down"}

    reporter = UsageReporter(InMemoryKv(), "t", 10)
    client = LlmClient(ErrorHttp(), reporter, base_url="http://litellm.test")
    with pytest.raises(RuntimeError, match="HTTP 500"):
        client.chat([{"role": "user", "content": "hi"}])
