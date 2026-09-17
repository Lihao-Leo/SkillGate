#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""平台 Agent Runner（SKILL.md 型技能的沙箱执行入口，镜像内置 /opt/platform/）。

执行模型（§4.7 契约的平台侧实现，包零改造）：
  load_input → SKILL.md + references 组装提示词 → 平台 LlmClient（usage 上报 + 硬闸）
  → 生成 JSON（解析失败自动修复轮）→ 包内确定性管线（可选 skill.json 声明）
  → save_artifact 登记 → scheduler 采集上传 OSS → 结算。

技能包可选 skill.json（包根）声明管线：
{
  "bridge": {
    "entryDoc": "SKILL.md",
    "contextFiles": [], "contextBudget": 40000, "model": "llm-text",
    "pipeline": [{"cmd": "scripts/render_output.py", "args": "{data} --out {out}/persona.md",
                  "artifact": "persona.md", "required": true}],
    "check": "scripts/persona_check.py"
  }
}
缺省（无 skill.json）：LLM 产物 JSON + 通用 Markdown 渲染两件产物，保证可交付。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(os.environ.get("SKILL_PACKAGE_ROOT", os.getcwd()))
DEFAULT_BUDGET = 24000
MAX_REPAIR_TURNS = 2


def _load_capabilities():
    """优先平台 toolset（镜像内 skill_worker.toolset / skill_sdk），本地自测降级。"""
    import sys as _sys
    _repo_root = Path(__file__).resolve().parents[2]
    if (_repo_root / "skill_worker").exists() and str(_repo_root) not in _sys.path:
        _sys.path.insert(0, str(_repo_root))
    try:
        from skill_worker.toolset import load_input, report_progress, save_artifact  # type: ignore
        from skill_worker.toolset.llm import LlmClient, UrllibHttpPort  # type: ignore
        from skill_worker.toolset.usage import reporter_from_env  # type: ignore
        return load_input, report_progress, save_artifact, LlmClient, UrllibHttpPort, reporter_from_env, True
    except ImportError:
        pass
    try:
        from toolset import load_input, report_progress, save_artifact  # type: ignore
        from toolset.llm import LlmClient, UrllibHttpPort  # type: ignore
        from toolset.usage import reporter_from_env  # type: ignore
        return load_input, report_progress, save_artifact, LlmClient, UrllibHttpPort, reporter_from_env, True
    except ImportError:
        pass
    try:
        from skill_sdk import load_input, report_progress, save_artifact  # type: ignore
        from skill_sdk.llm import LlmClient, UrllibHttpPort  # type: ignore
        from skill_sdk.usage import reporter_from_env  # type: ignore
        return load_input, report_progress, save_artifact, LlmClient, UrllibHttpPort, reporter_from_env, True
    except ImportError:
        pass

    # 本地自测降级（沙箱外直跑）
    def load_input(path=None):  # type: ignore[misc]
        target = path or os.environ.get("SKILL_INPUT")
        if not target and "--input" in sys.argv:
            target = sys.argv[sys.argv.index("--input") + 1]
        if not target:
            raise SystemExit("本地自测请 --input <input.json> 或设置 SKILL_INPUT")
        with open(target, encoding="utf-8") as handle:
            return json.load(handle)

    def report_progress(percent):  # type: ignore[misc]
        print(f"[progress] {percent}")

    def save_artifact(filename, content, type=None, meta=None):  # type: ignore[misc]
        out = Path(os.environ.get("BRIDGE_OUT", "artifacts"))
        out.mkdir(parents=True, exist_ok=True)
        if isinstance(content, (dict, list)):
            content = json.dumps(content, ensure_ascii=False)
        data = content if isinstance(content, bytes) else str(content).encode()
        (out / filename).write_bytes(data)
        print(f"[artifact] {filename}")

    class LlmClient:  # type: ignore[no-redef]
        def __init__(self, *args, **kwargs):
            self._base = os.environ.get("OPENAI_BASE_URL", "")
            self._key = os.environ.get("OPENAI_API_KEY", "")

        def chat(self, messages, model=None, **kwargs):
            import urllib.request
            body = json.dumps({"model": model or "llm-text", "messages": messages, **kwargs})
            req = urllib.request.Request(f"{self._base}/v1/chat/completions", data=body.encode(),
                                         method="POST", headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {self._key}"})
            try:
                with urllib.request.urlopen(req, timeout=llm_timeout) as resp:
                    payload = json.loads(resp.read().decode())
            except urllib.error.HTTPError as error:
                detail = error.read().decode(errors="replace")[:300]
                raise RuntimeError(f"model http {error.code}: {detail}") from None
            return payload["choices"][0]["message"]["content"]

    class UrllibHttpPort:  # type: ignore[no-redef]
        pass

    def reporter_from_env():  # type: ignore[misc]
        class _Noop:
            def check_budget(self):
                pass
        return _Noop()

    return load_input, report_progress, save_artifact, LlmClient, UrllibHttpPort, reporter_from_env, False


load_input, report_progress, save_artifact, LlmClient, UrllibHttpPort, reporter_from_env, IN_SANDBOX = _load_capabilities()


def log(step: str) -> None:
    print(f"[agent-runner] {step}", flush=True)


def load_config() -> dict:
    manifest = ROOT / "skill.json"
    if manifest.exists():
        try:
            with open(manifest, encoding="utf-8") as handle:
                return (json.load(handle) or {}).get("bridge", {})
        except ValueError as error:
            raise SystemExit(f"skill.json 解析失败: {error}")
    return {}


def auto_detect_pipeline(root: Path) -> list[dict]:
    """约定优于配置：探测技能包自带的确定性渲染脚本（Agent 生态常见约定）。

    scripts/render_output.py → 必需产物（schema 校验 + Markdown）
    scripts/render_report.py → 可选 HTML 报告
    """
    steps: list[dict] = []
    if (root / "scripts" / "render_output.py").exists():
        steps.append({"cmd": "scripts/render_output.py",
                      "args": "{data} --out {out}/persona.md",
                      "artifact": "persona.md", "required": True})
    if (root / "scripts" / "render_report.py").exists():
        steps.append({"cmd": "scripts/render_report.py",
                      "args": "{data} --out {out}/report.html",
                      "artifact": "report.html"})
    return steps


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
        text = path.read_text(encoding="utf-8", errors="replace")[: max(0, budget - used)]
        used += len(text)
        chunks.append(f"### {path.relative_to(ROOT)}\n\n{text}")
    doc = ROOT / (config.get("entryDoc") or "SKILL.md")
    skill_md = doc.read_text(encoding="utf-8", errors="replace") if doc.exists() else ""
    return f"{skill_md}\n\n---\n\n" + "\n\n---\n\n".join(chunks)


def build_messages(config: dict, context: str, data: dict) -> list[dict]:
    user_parts = []
    if data.get("context"):
        user_parts.append("【补充上下文】\n" + json.dumps(data["context"], ensure_ascii=False))
    if data.get("instructions"):
        user_parts.append("【任务指令】\n" + data["instructions"])
    user_parts.append("【本次输入数据】\n" + json.dumps(
        {k: v for k, v in data.items()
         if k not in ("materials", "instructions", "context", "count", "clientRequestId")},
        ensure_ascii=False, indent=1)[:60000])
    system = (
        context
        + "\n\n---\n\n【输出要求】严格按 references/output-schema.md（如存在）输出**单个 JSON 对象**，"
          "不要包裹 markdown 代码围栏，不要输出任何 JSON 以外的解释文字。"
    )
    user_content = "\n\n".join(user_parts)
    # qwen3 思考开关：默认关闭思考（思考会消耗大量生成时间并挤占输出额度）
    if config.get("noThink", True) and "qwen3" in config.get("model", "").lower():
        user_content += "\n/no_think"
    return [{"role": "system", "content": system},
            {"role": "user", "content": user_content}]


def extract_json(text: str) -> dict | None:
    fenced = re.search(r"```(?:json)?\s*(.+?)```", text, re.S)
    candidate = fenced.group(1) if fenced else text
    start = candidate.find("{")
    if start < 0:
        return None
    depth = 0
    in_str = False
    escape = False
    for index in range(start, len(candidate)):
        char = candidate[index]
        if in_str:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_str = False
        else:
            if char == '"':
                in_str = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(candidate[start:index + 1])
                    except ValueError:
                        return None
    return None


def generate(config: dict, data: dict, feedback: str | None = None) -> dict:
    client = LlmClient(UrllibHttpPort(), reporter_from_env()) if IN_SANDBOX else LlmClient()
    messages = build_messages(config, read_context(config), data)
    if feedback:
        messages.append({"role": "user",
                         "content": "【上一版输出未通过技能自检，请修正后重新输出完整 JSON】\n" + feedback})
    for attempt in range(1 + MAX_REPAIR_TURNS):
        extra_params = config.get("modelParams") or {}
        max_tokens = int(config.get("maxTokens", 8192))
        raw = client.chat(messages, model=config.get("model", "llm-text"),
                          max_tokens=max_tokens, **extra_params)
        parsed = extract_json(raw)
        if parsed is not None:
            log(f"LLM 输出 JSON 解析通过（第 {attempt + 1} 轮，{len(raw)} 字符）")
            return parsed
        log(f"JSON 解析失败（第 {attempt + 1} 轮），原始输出头 200 字符: {raw[:200]!r}")
        log(f"原始输出尾 120 字符: {raw[-120:]!r}")
        messages += [{"role": "assistant", "content": raw},
                     {"role": "user", "content": "输出不是合法 JSON 对象。请重新输出：只输出一个符合要求的完整 JSON 对象，无任何其他文字。"}]
        messages += [{"role": "assistant", "content": raw[:8000]},
                     {"role": "user", "content": "输出不是合法 JSON 对象。请重新输出：只输出一个符合要求的完整 JSON 对象，无任何其他文字。"}]
    raise SystemExit("模型多轮未能产出合法 JSON")


def render_html(data, title: str) -> str:
    """通用 JSON → HTML 视图（无第三方依赖；对象/数组递归渲染为卡片列表）。"""
    import html as html_module

    def render(value, depth=0):
        pad = 14 + depth * 18
        if isinstance(value, dict):
            rows = "".join(
                f'<div class="row"><div class="k">{html_module.escape(str(k))}</div>'
                f'<div class="v">{render(v, depth + 1)}</div></div>'
                for k, v in value.items())
            return f'<div class="card">{rows}</div>'
        if isinstance(value, list):
            items = "".join(f'<div class="item">{render(v, depth + 1)}</div>' for v in value)
            return f'<div class="list">{items}</div>'
        return f'<div class="scalar">{html_module.escape(str(value))}</div>'

    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html_module.escape(title)}</title>
<style>
body {{ font-family: -apple-system,'PingFang SC',sans-serif; background:#f5f6fa; margin:0; padding:32px; }}
h1 {{ font-size:20px; color:#1f2430; }}
.card {{ background:#fff; border:1px solid #e8eaf0; border-radius:12px; padding:16px 18px; margin:10px 0; }}
.row {{ display:flex; gap:14px; padding:6px 0; border-bottom:1px dashed #eef0f4; }}
.row:last-child {{ border-bottom:none; }}
.k {{ min-width:140px; color:#6b7280; font-size:13px; flex:none; }}
.v {{ flex:1; font-size:14px; }}
.item {{ padding:8px 0; border-bottom:1px dashed #eef0f4; }}
.scalar {{ white-space:pre-wrap; word-break:break-all; }}
</style></head><body>
<h1>{html_module.escape(title)}</h1>
{render(data)}
</body></html>"""


def run_pipeline(config: dict, plan: dict, workspace: Path) -> tuple[list[tuple[str, Path]], str | None]:
    """执行确定性管线。返回 (产物列表, 错误信息)；错误非 None 表示必需步骤失败，
    错误信息可反馈给 LLM 修复重生成（自检反馈闭环）。"""
    pipeline = config.get("pipeline") or auto_detect_pipeline(ROOT)
    if not pipeline:
        # 缺省兜底管线：JSON + Markdown + HTML 三形态，保证任意 AGENT 包可交付
        md = "# 执行结果\n\n```json\n" + json.dumps(plan, ensure_ascii=False, indent=1) + "\n```\n"
        save_artifact("result.json", plan, type="json")
        save_artifact("result.md", md, type="markdown")
        save_artifact("result.html", render_html(plan, "执行结果"), type="html")
        return [], None
    data_path = workspace / "data.json"
    data_path.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
    produced: list[tuple[str, Path]] = []
    for index, step in enumerate(pipeline):
        cmd = [sys.executable, str(ROOT / step["cmd"])]
        for token in step.get("args", "").split():
            token = token.replace("{data}", str(data_path)).replace("{out}", str(workspace))
            if token.startswith(str(workspace)):
                Path(token).parent.mkdir(parents=True, exist_ok=True)
            cmd.append(token)
        log(f"管线[{index}] {step['cmd']}")
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        if proc.stdout:
            print(proc.stdout[-4000:], flush=True)
        required = bool(step.get("required", index == 0))
        artifact = step.get("artifact")
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "")[-500:]
            if required:
                return [], f"必需产物步骤失败: {step['cmd']}（退出码 {proc.returncode}）{detail}"
            log(f"非必需步骤失败（忽略）: {step['cmd']}")
            continue
        if artifact:
            produced.append((artifact, workspace / artifact))
    return produced, None


def run_check(config: dict, artifacts: list[tuple[str, Path]], workspace: Path) -> None:
    check = config.get("check")
    primary = next((p for name, p in artifacts if name.endswith(".md")), None)
    if not check or primary is None:
        return
    proc = subprocess.run([sys.executable, str(ROOT / check), str(primary)],
                          cwd=ROOT, capture_output=True, text=True)
    save_artifact("skill_check.txt", f"exit={proc.returncode}\n\n{proc.stdout}\n\n{proc.stderr}",
                  type="text")
    if proc.returncode != 0:
        log("自检未通过：已登记 skill_check.txt 告警（不阻塞交付）")


def main() -> None:
    report_progress(5)
    config = load_config()
    data = load_input()
    data = data if isinstance(data, dict) else {"input": data}
    log(f"输入就绪：{len(json.dumps(data, ensure_ascii=False))} 字符")

    report_progress(15)
    workspace = Path(tempfile.mkdtemp(prefix="agent-runner-"))

    # 生成 → 管线校验 → 失败反馈 LLM 修复（最多 2 轮）
    artifacts: list[tuple[str, Path]] = []
    plan_error: str | None = None
    plan = generate(config, data)
    for attempt in range(2):
        report_progress(40 + attempt * 15)
        artifacts, plan_error = run_pipeline(config, plan, workspace)
        if plan_error is None:
            break
        if attempt == 0:
            log("校验失败，反馈 LLM 修复重生成")
            plan = generate(config, data, feedback=plan_error)
        else:
            break

    if not artifacts:
        save_artifact("data.json", plan, type="json")
    for name, path in artifacts:
        save_artifact(name, path.read_bytes())
        log(f"产物登记 {name}")

    report_progress(90)
    run_check(config, artifacts, workspace)
    report_progress(100)
    log("完成")


if __name__ == "__main__":
    main()
