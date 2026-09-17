#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""平台标准 Agent 桥（SKILL.md 型技能零改造接入，§4.7 契约的平台侧实现）。

适用：SKILL.md 指令 + references 知识 + scripts 确定性管线的 agent 技能
（Claude/Cursor 生态技能包通用）。平台沙箱执行本桥，包内容不受改造：

  1. load_input() 读取 input.json（业务背景/数据/可选材料预签名 URL）
  2. 组装提示词：SKILL.md（指令）+ references/*.md（知识，按预算截断）+ 入参
  3. 经平台 LlmClient（LiteLLM，usage 自动上报 + 上限硬闸）生成方案 JSON
  4. 按包内 bridge.json 声明的确定性管线渲染产物（技能自带校验/渲染脚本）
  5. save_artifact 登记 → worker 采集上传 OSS → 结算

skill.json（可选，包根）覆盖默认配置：
{
  "bridge": {
    "entryDoc": "SKILL.md",
    "contextFiles": [],                    // 空 = references/ 下全部 *.md
    "contextBudget": 40000,                // 知识文件总字符预算
    "model": "llm-text",
    "pipeline": [                          // 首个 required 产物失败 = 任务失败
      {"cmd": "scripts/render_output.py", "args": "{data} --out {out}/persona.md",
       "artifact": "persona.md", "required": true},
      {"cmd": "scripts/render_report.py", "args": "{data} --out {out}/report.html",
       "artifact": "report.html"}
    ],
    "check": "scripts/persona_check.py"    // 软门禁：失败记告警产物，不阻塞
  }
}
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_BUDGET = 40000
MAX_REPAIR_TURNS = 2

# ---------- 沙箱能力（skill-sdk 缺席时降级为本地直跑，便于包外自测） ----------
try:
    from skill_sdk import load_input, report_progress, save_artifact
    from skill_sdk.llm import LlmClient, UrllibHttpPort
    from skill_sdk.usage import reporter_from_env

    IN_SANDBOX = True
except ImportError:  # 本地自测：--input file 读参，产物写 stdout 目录
    IN_SANDBOX = False

    def load_input(path: str | None = None) -> dict:  # type: ignore[misc]
        target = path or os.environ.get("SKILL_INPUT") or sys.argv[
            sys.argv.index("--input") + 1] if "--input" in sys.argv else None
        if not target:
            raise SystemExit("本地自测请用 --input <input.json>（或设置 SKILL_INPUT）")
        with open(target, encoding="utf-8") as handle:
            return json.load(handle)

    def report_progress(percent: int) -> None:  # type: ignore[misc]
        print(f"[progress] {percent}")

    def save_artifact(filename: str, content, type: str | None = None,  # type: ignore[misc]
                      meta: dict | None = None) -> None:
        out = Path(os.environ.get("BRIDGE_OUT", "artifacts"))
        out.mkdir(parents=True, exist_ok=True)
        data = content if isinstance(content, bytes) else str(content).encode()
        (out / filename).write_bytes(data)
        print(f"[artifact] {filename} ({type or 'file'})")

    class LlmClient:  # type: ignore[no-redef]
        def __init__(self, *args, **kwargs):
            self._base = os.environ.get("OPENAI_BASE_URL", "")
            self._key = os.environ.get("OPENAI_API_KEY", "")
            self._model = "llm-text"

        def chat(self, messages, model=None, **kwargs):
            import urllib.request
            body = json.dumps({"model": model or self._model, "messages": messages, **kwargs})
            req = urllib.request.Request(f"{self._base}/v1/chat/completions", data=body.encode(),
                                         method="POST", headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {self._key}"})
            with urllib.request.urlopen(req, timeout=300) as resp:
                payload = json.loads(resp.read().decode())
            return payload["choices"][0]["message"]["content"]

    def reporter_from_env():  # type: ignore[misc]
        class _Noop:
            def check_budget(self) -> None:
                pass

        return _Noop()

    class UrllibHttpPort:  # type: ignore[no-redef]
        pass


def log(step: str) -> None:
    print(f"[bridge] {step}", flush=True)


def load_config() -> dict:
    manifest = ROOT / "skill.json"
    if manifest.exists():
        try:
            with open(manifest, encoding="utf-8") as handle:
                return (json.load(handle) or {}).get("bridge", {})
        except ValueError as error:
            raise SystemExit(f"skill.json 解析失败: {error}")
    return {}


def read_context(config: dict) -> str:
    budget = int(config.get("contextBudget", DEFAULT_BUDGET))
    explicit = list(config.get("contextFiles") or [])
    if explicit:
        files = [ROOT / rel for rel in explicit]
    else:
        refs = ROOT / "references"
        files = sorted(refs.glob("**/*.md")) if refs.exists() else []
    chunks: list[str] = []
    used = 0
    for path in files:
        if not path.is_file() or used >= budget:
            break
        text = path.read_text(encoding="utf-8", errors="replace")
        text = text[: max(0, budget - used)]
        used += len(text)
        rel = path.relative_to(ROOT)
        chunks.append(f"### {rel}\n\n{text}")
    doc = ROOT / (config.get("entryDoc") or "SKILL.md")
    skill_md = doc.read_text(encoding="utf-8", errors="replace") if doc.exists() else ""
    return f"{skill_md}\n\n---\n\n" + "\n\n---\n\n".join(chunks)


def build_messages(config: dict, context: str, data: dict) -> list[dict]:
    instructions = data.get("instructions") or ""
    context_obj = data.get("context")
    user_parts = []
    if context_obj:
        user_parts.append("【补充上下文】\n" + json.dumps(context_obj, ensure_ascii=False))
    if instructions:
        user_parts.append("【任务指令】\n" + instructions)
    # materials 经平台换预签名 URL，由模型在回复中按需引用
    user_parts.append("【本次输入数据】\n" + json.dumps(
        {k: v for k, v in data.items()
         if k not in ("materials", "instructions", "context", "count", "clientRequestId")},
        ensure_ascii=False, indent=1)[:60000])
    system = (
        context
        + "\n\n---\n\n【输出要求】严格按 references/output-schema.md（如存在）输出**单个 JSON 对象**，"
          "不要包裹 markdown 代码围栏，不要输出任何 JSON 以外的解释文字。"
    )
    return [{"role": "system", "content": system},
            {"role": "user", "content": "\n\n".join(user_parts)}]


def extract_json(text: str) -> dict | None:
    fenced = re.search(r"```(?:json)?\s*(.+?)```", text, re.S)
    candidate = fenced.group(1) if fenced else text
    start = candidate.find("{")
    if start < 0:
        return None
    depth = 0
    for index in range(start, len(candidate)):
        if candidate[index] == "{":
            depth += 1
        elif candidate[index] == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(candidate[start:index + 1])
                except ValueError:
                    return None
    return None


def generate(config: dict, data: dict) -> dict:
    client = LlmClient(UrllibHttpPort(), reporter_from_env()) if IN_SANDBOX else LlmClient()
    model = config.get("model", "llm-text")
    messages = build_messages(config, read_context(config), data)
    for attempt in range(1 + MAX_REPAIR_TURNS):
        raw = client.chat(messages, model=model)
        parsed = extract_json(raw)
        if parsed is not None:
            log(f"LLM 输出 JSON 解析通过（第 {attempt + 1} 轮）")
            return parsed
        log(f"JSON 解析失败（第 {attempt + 1} 轮），请求修复")
        messages = messages + [
            {"role": "assistant", "content": raw[:8000]},
            {"role": "user", "content": "输出不是合法 JSON 对象。请重新输出：只输出一个符合要求的完整 JSON 对象，无任何其他文字。"},
        ]
    raise SystemExit("模型多轮未能产出合法 JSON（见上方报错）")


def run_pipeline(config: dict, data: dict, workspace: Path) -> list[tuple[str, Path]]:
    """确定性管线：返回 [(artifact 名, 文件路径)]；首个 required 失败 = 任务失败。"""
    pipeline = config.get("pipeline") or []
    data_path = workspace / "data.json"
    data_path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    produced: list[tuple[str, Path]] = []
    for index, step in enumerate(pipeline):
        cmd = [sys.executable, str(ROOT / step["cmd"])]
        for token in step.get("args", "").split():
            token = (token.replace("{data}", str(data_path))
                     .replace("{out}", str(workspace)))
            if not Path(token).exists() and token.startswith(str(workspace)):
                Path(token).parent.mkdir(parents=True, exist_ok=True)
            cmd.append(token)
        log(f"管线[{index}] {step['cmd']}")
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        if proc.stdout:
            print(proc.stdout[-4000:], flush=True)
        required = bool(step.get("required", index == 0))
        artifact = step.get("artifact")
        if proc.returncode != 0:
            if required:
                sys.stderr.write(proc.stderr[-4000:])
                raise SystemExit(f"必需产物步骤失败: {step['cmd']}（退出码 {proc.returncode}）")
            log(f"非必需步骤失败（忽略）: {step['cmd']}")
            continue
        if artifact:
            produced.append((artifact, workspace / artifact))
    return produced


def run_check(config: dict, artifacts: list[tuple[str, Path]], workspace: Path) -> None:
    check = config.get("check")
    if not check:
        return
    primary = next((p for name, p in artifacts if name.endswith(".md")), None)
    if primary is None:
        return
    proc = subprocess.run([sys.executable, str(ROOT / check), str(primary)],
                          cwd=ROOT, capture_output=True, text=True)
    report = f"exit={proc.returncode}\n\n{proc.stdout}\n\n{proc.stderr}"
    save_artifact("persona_check.txt", report, type="text")
    if proc.returncode != 0:
        log("自检未通过：已登记 persona_check.txt 告警（不阻塞交付）")


def main() -> None:
    report_progress(5)
    config = load_config()
    data = load_input()
    data = data if isinstance(data, dict) else {"input": data}
    log(f"输入就绪：{len(json.dumps(data, ensure_ascii=False))} 字符")

    report_progress(15)
    plan = generate(config, data)
    workspace = Path(tempfile.mkdtemp(prefix="bridge-"))
    (workspace / "data.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1),
                                         encoding="utf-8")
    save_artifact("data.json", plan, type="json")

    report_progress(55)
    artifacts = run_pipeline(config, plan, workspace)
    if not artifacts:
        # 包未声明管线：直接把模型 JSON 交付（保底可交付）
        save_artifact("persona.json", plan, type="json")
    for name, path in artifacts:
        save_artifact(name, path.read_bytes())
        log(f"产物登记 {name}")

    report_progress(90)
    run_check(config, artifacts, workspace)
    report_progress(100)
    log("完成")


if __name__ == "__main__":
    main()
