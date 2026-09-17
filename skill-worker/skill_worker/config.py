"""环境变量驱动配置（K8s Deployment env / 本地 .env 自动加载，已设置的变量不覆盖）。"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv() -> None:
    """项目根 .env 轻量加载（无第三方依赖；已有环境变量优先）。"""
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip("\"").strip("'"))


_load_dotenv()


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass
class Settings:
    # MySQL（dev 单库 dev-skill；生产为 skill_platform 分库，经 env 覆盖）
    mysql_host: str = os.environ.get("MYSQL_HOST", "127.0.0.1")
    mysql_port: int = _int("MYSQL_PORT", 3306)
    mysql_db: str = os.environ.get("MYSQL_DB", "dev-skill")
    mysql_user: str = os.environ.get("MYSQL_USER", "root")
    mysql_password: str = os.environ.get("MYSQL_PASSWORD", "")

    # Redis（usage / progress / 看门狗防抖计数）
    redis_url: str = os.environ.get("REDIS_URL", "redis://127.0.0.1:6379/0")

    # OSS（S3 兼容，全私有桶；对外一律预签名；OSS / COS 通用）
    oss_endpoint: str = os.environ.get("OSS_ENDPOINT", "https://cos.ap-guangzhou.myqcloud.com")
    oss_region: str = os.environ.get("OSS_REGION", "ap-guangzhou")
    oss_bucket: str = os.environ.get("OSS_BUCKET", "")
    oss_access_key: str = os.environ.get("OSS_ACCESS_KEY", "")
    oss_secret_key: str = os.environ.get("OSS_SECRET_KEY", "")
    # 桶内统一前缀：与 gateway 的 skill-platform.storage.key-prefix 保持一致
    oss_key_prefix: str = os.environ.get("OSS_KEY_PREFIX", "skill-platform/")

    # K8s（namespace / 沙箱 PVC 工作区挂载路径）
    k8s_namespace: str = os.environ.get("K8S_NAMESPACE", "skill-platform")
    workspace_root: str = os.environ.get("WORKSPACE_ROOT", "/workspace")
    sandbox_base_image: str = os.environ.get("SANDBOX_BASE_IMAGE", "skill-sandbox/base-python:latest")
    sandbox_video_image: str = os.environ.get("SANDBOX_VIDEO_IMAGE", "skill-sandbox/base-python-ffmpeg:latest")

    # LiteLLM（模型供给，Job 内 env 注入）
    litellm_base_url: str = os.environ.get("LITELLM_BASE_URL", "http://litellm.platform.svc:4000")
    litellm_api_key: str = os.environ.get("LITELLM_API_KEY", "sk-platform-internal")

    # billing-service（结算 RPC 主路径）
    billing_base_url: str = os.environ.get("BILLING_BASE_URL", "http://localhost:8081")
    billing_internal_token: str = os.environ.get("INTERNAL_TOKEN", "dev-internal-token")

    # RocketMQ（namesrv 供 mqadmin；worker/业务端走 gRPC Proxy）
    mq_namesrv: str = os.environ.get("ROCKETMQ_NAMESRV", "127.0.0.1:9876")
    mq_proxy_endpoint: str = os.environ.get("ROCKETMQ_PROXY", "127.0.0.1:8081")
    topic_execute: str = "skill-execute"
    topic_settle: str = "skill-settle"
    topic_callback_retry: str = "skill-callback-retry"
    topic_callback_dlq: str = "skill-callback-dlq"

    # 凭据加密密钥（与 gateway 共享，base64 32B；AppSecret 解密用于回调 HMAC）
    crypto_key: str = os.environ.get("CRYPTO_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")

    # 看门狗（§4.10）
    pending_requeue_seconds: int = _int("WATCHDOG_PENDING_REQUEUE_SECONDS", 300)
    pending_fail_seconds: int = _int("WATCHDOG_PENDING_FAIL_SECONDS", 1800)
    running_grace_seconds: int = _int("WATCHDOG_RUNNING_GRACE_SECONDS", 300)
    watchdog_interval_seconds: int = _int("WATCHDOG_INTERVAL_SECONDS", 60)

    # 产物上限（§4.7：单文件 ≤500MB、总量 ≤2GB；数量 ≤ maxCount×2 / countable=false ≤4）
    artifact_max_file_bytes: int = 500 * 1024 * 1024
    artifact_max_total_bytes: int = 2 * 1024 * 1024 * 1024
    artifact_default_max_count: int = 4

    # 全局硬顶（§4.1：timeoutSeconds 缺省 600，硬顶 3600）
    default_timeout_seconds: int = 600
    hard_cap_timeout_seconds: int = 3600

    # METERED 用量折算默认单价（点数；model_provider 表可覆盖）
    metered_default_cost_per_1k_input: float = float(os.environ.get("METERED_COST_1K_IN", "0.01"))
    metered_default_cost_per_1k_output: float = float(os.environ.get("METERED_COST_1K_OUT", "0.02"))
    metered_default_cost_per_video_call: float = float(os.environ.get("METERED_COST_VIDEO", "10"))

    # 沙箱模式：local=subprocess 直跑（无 K8s 本地联调）；k8s=生产 Job
    sandbox_mode: str = os.environ.get("SANDBOX_MODE", "local")
    agent_runner_path: str = os.environ.get(
        "AGENT_RUNNER_PATH",
        str(Path(__file__).resolve().parents[1] / "sandbox-images" / "base-python" / "agent_runner.py"))

    # 单任务模型调用上限硬闸（§4.5：真跑飞了的闸门）
    task_model_call_limit: int = _int("TASK_MODEL_CALL_LIMIT", 500)

    # 结算 RPC 超时与重试
    settle_timeout_seconds: float = 5.0
    settle_rpc_retries: int = 1

    # 回调（§5.5）
    callback_timeout_seconds: float = 10.0
    callback_max_attempts: int = 3
    # RocketMQ 延迟等级：5=1min, 9=5min, 14=15min（退避 1/5/15）
    callback_delay_levels: tuple = (5, 9, 14)

    # 消息循环
    watch_poll_seconds: float = float(os.environ.get("WATCH_POLL_SECONDS", "2"))
    usage_ttl_seconds: int = 24 * 3600


def load_settings() -> Settings:
    return Settings()
