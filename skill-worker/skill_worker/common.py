"""通用工具：ID 生成（与平台风格一致：前缀 + 时间戳 + 随机段）。"""

from __future__ import annotations

import secrets
from datetime import datetime


def new_id(prefix: str) -> str:
    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    return f"{prefix}{timestamp}_{secrets.token_hex(4)}"
