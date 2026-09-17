"""KV 端口：usage / progress / 看门狗防抖计数（生产 Redis，测试进程内）。"""

from __future__ import annotations

from typing import Protocol


class KvPort(Protocol):
    def get(self, key: str) -> str | None: ...

    def set(self, key: str, value: str, ttl_seconds: int | None = None) -> None: ...

    def hincrby(self, key: str, field: str, delta: int = 1) -> int: ...

    def hgetall(self, key: str) -> dict[str, str]: ...

    def expire(self, key: str, ttl_seconds: int) -> None: ...

    def incr(self, key: str, ttl_seconds: int | None = None) -> int: ...

    def delete(self, key: str) -> None: ...


class InMemoryKv:
    """进程内实现（测试）：hash 以 dict 承载，惰性 TTL。"""

    def __init__(self) -> None:
        self._strings: dict[str, str] = {}
        self._hashes: dict[str, dict[str, str]] = {}
        self._expires: dict[str, float] = {}
        self._now = 0.0

    # 测试可注入时钟（秒）
    def tick(self, seconds: float) -> None:
        self._now += seconds

    def _alive(self, key: str) -> bool:
        expiry = self._expires.get(key)
        return expiry is None or self._now < expiry

    def get(self, key: str) -> str | None:
        return self._strings.get(key) if self._alive(key) else None

    def set(self, key: str, value: str, ttl_seconds: int | None = None) -> None:
        self._strings[key] = value
        if ttl_seconds is not None:
            self._expires[key] = self._now + ttl_seconds
        else:
            self._expires.pop(key, None)

    def hincrby(self, key: str, field: str, delta: int = 1) -> int:
        bucket = self._hashes.setdefault(key, {})
        current = int(bucket.get(field, 0))
        current += delta
        bucket[field] = str(current)
        return current

    def hgetall(self, key: str) -> dict[str, str]:
        return dict(self._hashes.get(key, {})) if self._alive(key) else {}

    def expire(self, key: str, ttl_seconds: int) -> None:
        self._expires[key] = self._now + ttl_seconds

    def incr(self, key: str, ttl_seconds: int | None = None) -> int:
        value = int(self._strings.get(key, 0)) + 1 if self._alive(key) else 1
        self._strings[key] = str(value)
        if ttl_seconds is not None:
            self._expires[key] = self._now + ttl_seconds
        return value

    def delete(self, key: str) -> None:
        self._strings.pop(key, None)
        self._hashes.pop(key, None)
        self._expires.pop(key, None)


class RedisKv:
    """生产：redis-py（task:usage / task:progress / 看门狗计数）。"""

    def __init__(self, url: str):
        import redis

        self._redis = redis.Redis.from_url(url, decode_responses=True)

    def get(self, key: str) -> str | None:
        return self._redis.get(key)

    def set(self, key: str, value: str, ttl_seconds: int | None = None) -> None:
        self._redis.set(key, value, ex=ttl_seconds)

    def hincrby(self, key: str, field: str, delta: int = 1) -> int:
        return int(self._redis.hincrby(key, field, delta))

    def hgetall(self, key: str) -> dict[str, str]:
        return {k.decode() if isinstance(k, bytes) else k: v.decode() if isinstance(v, bytes) else v
                for k, v in self._redis.hgetall(key).items()}

    def expire(self, key: str, ttl_seconds: int) -> None:
        self._redis.expire(key, ttl_seconds)

    def incr(self, key: str, ttl_seconds: int | None = None) -> int:
        value = self._redis.incr(key)
        if value == 1 and ttl_seconds is not None:
            self._redis.expire(key, ttl_seconds)
        return int(value)

    def delete(self, key: str) -> None:
        self._redis.delete(key)
