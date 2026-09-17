"""LLM 调用薄封装：已用 OpenAI SDK 的 Skill 零改动（env 重定向 LiteLLM，§4.5）。

本封装提供：经平台 LiteLLM 的 chat（含 usage 上报与单任务调用上限硬闸）；
不装本包直接用 OpenAI SDK 同样成立（spend 日志经 LiteLLM 侧归并）。
"""

from __future__ import annotations

import json
import os
import urllib.request
from typing import Any, Protocol


class HttpPort(Protocol):
    def post_json(self, url: str, body_json: str, headers: dict[str, str],
                  timeout_seconds: float) -> tuple[int, dict]:
        """返回 (status, parsed_json)；传输异常抛 IOError"""
        ...


class UrllibHttpPort:
    def post_json(self, url: str, body_json: str, headers: dict[str, str],
                  timeout_seconds: float) -> tuple[int, dict]:
        request = urllib.request.Request(url, data=body_json.encode(), method="POST", headers={
            "Content-Type": "application/json", **headers})
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                return response.status, json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            try:
                return error.code, json.loads(error.read().decode())
            except Exception:  # noqa: BLE001
                return error.code, {}


class LlmClient:
    """OpenAI 兼容 /chat/completions（默认经 env OPENAI_BASE_URL / OPENAI_API_KEY 走 LiteLLM）。"""

    def __init__(self, http: HttpPort, usage_reporter, base_url: str | None = None,
                 api_key: str | None = None, timeout_seconds: float = 120.0,
                 task_id: str = ""):
        self._http = http
        self._usage = usage_reporter
        self._base_url = (base_url or os.environ.get("OPENAI_BASE_URL",
                                                     "http://litellm.platform.svc:4000")).rstrip("/")
        self._api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self._timeout = timeout_seconds
        self._task_id = task_id

    def chat(self, messages: list[dict], model: str = "llm-text",
             **kwargs: Any) -> str:
        """单轮对话；usage 自动上报 + 上限硬闸（超限抛 BudgetExceeded）。"""
        self._usage.check_budget()
        body = json.dumps({"model": model, "messages": messages, **kwargs})
        status, payload = self._http.post_json(
            f"{self._base_url}/v1/chat/completions", body,
            {"Authorization": f"Bearer {self._api_key}",
             "X-Task-Id": self._task_id},
            self._timeout)
        if status != 200 or "choices" not in payload:
            raise RuntimeError(f"llm chat failed: HTTP {status} {payload.get('error', '')}")
        usage = payload.get("usage") or {}
        self._usage.report_llm(int(usage.get("prompt_tokens", 0)),
                               int(usage.get("completion_tokens", 0)))
        message = payload["choices"][0].get("message") or {}
        # 思考模型兼容（GLM-5/DeepSeek-R1 等）：content 空时回退 reasoning_content
        return message.get("content") or message.get("reasoning_content") or ""
