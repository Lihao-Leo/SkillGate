#!/bin/sh
# 沙箱 Job 入口（§3.4 executor / §4.7 契约）：
#   工作区由 scheduler 预置（共享 PVC）：input.json（env SKILL_INPUT）+ 已解压的 Skill 包
#   入口分流：main.py = 代码技能（python main.py）；
#            SKILL.md = 智能体技能（平台内置 agent runner 执行，包零改造）
# 退出码：0 = 正常结束（产物有无由 scheduler 按 EMPTY_OUTPUT 规则判定）；非 0 = 失败
set -e

WORKDIR="$(dirname "$SKILL_INPUT")"
cd "$WORKDIR"
export SKILL_PACKAGE_ROOT="$WORKDIR"

# 取消联动（§4.5）：SIGTERM（activeDeadlineSeconds 到点 / 用户取消）时尽力清理
trap 'exit 143' TERM INT

# pip 安装落 emptyDir/PVC（readOnlyRootFilesystem 兼容）；同版本重复执行零安装开销
if [ -f requirements.txt ]; then
  pip install --no-input --no-warn-script-location -r requirements.txt || {
    echo "[skill-entrypoint] pip install failed" >&2
    exit 1
  }
fi

# 入口分流（按包类型，gateway 上传时已识别并登记 skill.kind）
if [ -f main.py ]; then
  exec python main.py
fi
if [ -f SKILL.md ]; then
  exec python /opt/platform/agent_runner.py
fi

echo "[skill-entrypoint] 包缺少入口：需 main.py 或 SKILL.md" >&2
exit 1
