"""Skill 包契约封装（§4.7）：load_input / save_artifact / report_progress。

- load_input：读 env SKILL_INPUT 指向的 input.json（不走 stdin / 命令行参数）
- save_artifact：写工作区 artifacts/ 并登记 manifest.json（type/meta）
- report_progress：进度 0-100 写 Redis task:progress:{taskId}（TTL 由平台管理）
"""

from __future__ import annotations

import json
import os

from .usage import kv_from_env


def load_input(path: str | None = None) -> dict:
    """读取完整入参：materials（OSS 项已换预签名 URL）/ instructions / context / count。"""
    input_path = path or os.environ.get("SKILL_INPUT", "input.json")
    with open(input_path, encoding="utf-8") as handle:
        return json.load(handle)


def _artifacts_dir() -> str:
    base = os.path.dirname(os.environ.get("SKILL_INPUT", "input.json"))
    return os.path.join(base, "artifacts")


def save_artifact(filename: str, content: bytes | str | dict | list,
                  type: str | None = None, meta: dict | None = None) -> None:
    """产物登记：写 artifacts/{filename} 并更新 manifest.json（scheduler 采集依据）。"""
    artifacts = _artifacts_dir()
    os.makedirs(artifacts, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, ensure_ascii=False)
    if isinstance(content, str):
        content = content.encode()
    with open(os.path.join(artifacts, filename), "wb") as handle:
        handle.write(content)

    manifest_path = os.path.join(artifacts, "manifest.json")
    manifest: dict = {"files": []}
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, encoding="utf-8") as handle:
                manifest = json.load(handle)
        except (ValueError, OSError):
            manifest = {"files": []}
    entries = [entry for entry in manifest.get("files", [])
               if entry.get("filename") != filename]
    entries.append({"filename": filename, "type": type or _infer(filename),
                    "meta": meta or {}})
    manifest["files"] = entries
    with open(manifest_path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False)


def report_progress(percent: int) -> None:
    """进度上报（0-100）：Redis task:progress:{taskId}，查询接口经 gateway 读取。"""
    percent = max(0, min(100, int(percent)))
    task_id = os.environ.get("SKILL_TASK_ID")
    if not task_id:
        return
    kv = kv_from_env()
    kv.set(f"task:progress:{task_id}", str(percent), ttl_seconds=24 * 3600)


def _infer(filename: str) -> str:
    from ..scheduler.artifacts import infer_type

    return infer_type(filename)
