# Agent 技能标准桥（平台标准框架）

让 **SKILL.md 型技能包零改造**接入平台（§4.7 契约的平台侧实现）。
平台沙箱只认一个固定入口 `main.py`——本桥就是那个入口：包内其余文件保持原样。

## 适用

SKILL.md（指令）+ references/（知识）+ scripts/（确定性校验/渲染脚本）的
agent 技能，Claude/Cursor 生态的技能包基本都是这个形态。

## 接入（3 步，不改技能内容）

1. **拷入**本目录的 `main.py` 到技能包根目录
2. **（可选）**加 `skill.json` 声明确定性管线（默认约定见 main.py 头注释）：
   ```json
   {
     "bridge": {
       "entryDoc": "SKILL.md",
       "pipeline": [
         {"cmd": "scripts/render_output.py", "args": "{data} --out {out}/persona.md",
          "artifact": "persona.md", "required": true},
         {"cmd": "scripts/render_report.py", "args": "{data} --out {out}/report.html",
          "artifact": "report.html"}
       ],
       "check": "scripts/persona_check.py"
     }
   }
   ```
3. **打包**：排除 `__pycache__/`、`*.pyc` 后 zip，经管理后台上传

## 执行流（沙箱内）

```
load_input → SKILL.md+references 组装提示词 → LlmClient(LiteLLM, usage 自动上报)
  → 生成 JSON（失败自动修复轮）→ 管线脚本渲染产物 → save_artifact 登记
```

- 产物 = 技能自己的脚本渲染（校验逻辑不重复实现，包自治）
- `data.json`（模型原始方案）一并登记，供复现/对账
- 计费：模型调用走 METERED 用量上报；按次平台可配 PER_EXECUTION 冻结

## 本地自测（沙箱外）

```bash
export OPENAI_BASE_URL=http://litellm.local:4000 OPENAI_API_KEY=sk-xxx
python main.py --input input.json   # 产物写 ./artifacts/
```
