# 沙箱执行镜像：base-python（纯 LLM 类，§4.6 镜像矩阵）
# toolset 内置 = skill-sdk 薄包等价（load_input / save_artifact / report_progress / llm / video_gen / kb_query）
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 平台 toolset（含 cryptography：AES/凭据；模型调用经 OPENAI_* env 走 LiteLLM）
COPY --from=skill-worker-scheduler:latest /app/skill_worker /opt/skill_worker
ENV PYTHONPATH=/opt/skill_worker

# wheelhouse 缓存与 pip 安装落共享 PVC（与 readOnlyRootFilesystem 兼容，§4.6）
ENV PIP_CACHE_DIR=/workspace/.wheelhouse \
    PIP_TARGET=/workspace/.site-packages \
    PYTHONPATH=/opt/skill_worker:/workspace/.site-packages

# 沙箱入口：pip 安装 → 运行 Skill 黑盒（main.py 代码技能 / SKILL.md 智能体技能）
COPY skill-entrypoint.sh /usr/local/bin/skill-entrypoint
COPY agent_runner.py /opt/platform/agent_runner.py
RUN chmod +x /usr/local/bin/skill-entrypoint

# 非 root（配合 Pod securityContext runAsNonRoot）
USER 1000:1000

ENTRYPOINT ["/usr/local/bin/skill-entrypoint"]
