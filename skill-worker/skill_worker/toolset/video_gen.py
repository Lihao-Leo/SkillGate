"""video_gen（§4.5）：特殊协议提交 / 轮询 / 取结果（不经 LiteLLM，toolset 自实现回退链）。

- 进度映射：提交=10%、轮询中 10→90% 线性、取回=95%（count>1 时 Skill 可 report_progress 覆盖）
- 超时分层：单次 provider 调用超时（默认 300s）< execution.timeoutSeconds（外层总闸）
- 取消联动：SIGTERM handler 尽力取消 provider 侧任务（best-effort）由镜像入口承接
"""

from __future__ import annotations

import time
from typing import Protocol


class VideoProvider(Protocol):
    """供应商适配端口（特殊协议）：submit 返回 task_id；poll 返回 done/url/error。"""

    def alias(self) -> str: ...

    def submit(self, prompt: str, options: dict) -> str: ...

    def poll(self, provider_task_id: str) -> dict:
        """{status: RUNNING|SUCCEEDED|FAILED, url?: str, error?: str}"""
        ...

    def cancel(self, provider_task_id: str) -> None: ...


class VideoGenError(RuntimeError):
    pass


class VideoGenTimeout(VideoGenError):
    pass


class VideoGen:
    def __init__(self, providers: list[VideoProvider], usage_reporter,
                 poll_interval_seconds: float = 5.0, max_poll_seconds: int = 300,
                 poll_backoff_cap_seconds: float = 30.0, progress=None):
        if not providers:
            raise ValueError("at least one provider required (fallback chain)")
        self._providers = providers
        self._usage = usage_reporter
        self._poll_interval = poll_interval_seconds
        self._max_poll = max_poll_seconds
        self._backoff_cap = poll_backoff_cap_seconds
        self._progress = progress  # callable(percent) 可选（映射到 report_progress）

    def generate(self, prompt: str, options: dict | None = None) -> str:
        """提交 → 轮询（指数退避至 30s）→ 取结果；逐级回退（最后一档失败抛 VideoGenError）。"""
        options = dict(options or {})
        self._usage.check_budget()  # 单任务调用上限硬闸：不被回退链吞掉（超限直接抛出）
        last_error: Exception = VideoGenError("no provider available")
        notes: list[str] = []
        for provider in self._providers:
            try:
                return self._run_one(provider, prompt, options)
            except Exception as error:  # noqa: BLE001 - Provider 任何失败均走回退链
                last_error = error
                notes.append(f"{provider.alias()}: {error}")
                continue
        if isinstance(last_error, VideoGenTimeout):
            raise last_error  # 全链耗尽仍是超时语义（内层先到，外层总闸兜底）
        raise VideoGenError(f"all providers failed: {'; '.join(notes)}")

    def _run_one(self, provider: VideoProvider, prompt: str, options: dict) -> str:
        timeout = int(options.get("timeout", self._max_poll))
        provider_task = provider.submit(prompt, options)
        self._notify(10)
        self._usage.report_video_call()

        deadline = time.monotonic() + min(timeout, self._max_poll) \
            if timeout else time.monotonic() + self._max_poll
        interval = self._poll_interval
        while time.monotonic() < deadline:
            state = provider.poll(provider_task)
            status = state.get("status", "RUNNING")
            if status == "SUCCEEDED" and state.get("url"):
                self._notify(95)
                return state["url"]
            if status == "FAILED":
                raise VideoGenError(f"provider failed: {state.get('error', 'unknown')}")
            # 轮询中：10→90 线性（按剩余时间近似）
            elapsed_ratio = 1.0 - (deadline - time.monotonic()) / max(
                deadline - (deadline - min(timeout, self._max_poll)), 1)
            self._notify(int(10 + 80 * min(max(elapsed_ratio, 0), 1)))
            time.sleep(interval)
            interval = min(interval * 1.5, self._backoff_cap)  # 指数退避至 30s
        raise VideoGenTimeout(f"provider poll exceeded {timeout}s")

    def _notify(self, percent: int) -> None:
        if self._progress:
            try:
                self._progress(percent)
            except Exception:  # noqa: BLE001 - 进度上报失败不影响生成
                pass
