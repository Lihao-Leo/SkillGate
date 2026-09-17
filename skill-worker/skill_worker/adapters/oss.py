"""对象存储端口：OSS 全私有桶（产物上传 / 包与入参读取 / 预签名 GET）。"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Protocol

from ..domain import ArtifactFile


class OssPort(Protocol):
    def get(self, key: str) -> bytes: ...

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None: ...

    def presign_get(self, key: str, ttl_seconds: int = 24 * 3600) -> str: ...


class InMemoryOss:
    """进程内实现（测试）：objects 字典 + 确定性伪预签名。"""

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.content_types: dict[str, str] = {}

    def get(self, key: str) -> bytes:
        if key not in self.objects:
            raise KeyError(f"oss object not found: {key}")
        return self.objects[key]

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        self.objects[key] = data
        self.content_types[key] = content_type

    def presign_get(self, key: str, ttl_seconds: int = 24 * 3600) -> str:
        expires = datetime.now() + timedelta(seconds=ttl_seconds)
        return f"https://oss.local/{key}?presign=get&expires={int(expires.timestamp())}"

    def artifact_key(self, tenant_id: str, task_id: str, filename: str) -> str:
        return f"{tenant_id}/artifacts/{task_id}/{filename}"


class S3Oss:
    """生产：S3 兼容对象存储（OSS / COS，boto3）。

    寻址用 virtual-host（``<bucket>.<endpoint>``）：COS 标准端点不支持 path-style（实测 403），
    阿里云 OSS 两者皆可，virtual 为通用正确选择。
    桶内统一前缀：物理 key = key_prefix + 逻辑 key（与 gateway S3ObjectStorage 约定一致，
    两侧 prefix 必须相同；逻辑 key 落库不加前缀）。
    """

    def __init__(self, endpoint: str, region: str, bucket: str, access_key: str, secret_key: str,
                 key_prefix: str = ""):
        import boto3
        from botocore.config import Config

        self._bucket = bucket
        self._key_prefix = key_prefix
        self._client = boto3.client(
            "s3", endpoint_url=endpoint, region_name=region,
            aws_access_key_id=access_key, aws_secret_access_key=secret_key,
            config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"}),
        )

    def _full(self, key: str) -> str:
        return f"{self._key_prefix}{key}"

    def get(self, key: str) -> bytes:
        full_key = self._full(key)
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=full_key)
            return response["Body"].read()
        except Exception:
            import logging
            logging.getLogger("skill_worker").error(
                "[oss-diagnostics] get failed: bucket=%s full_key=%s endpoint=%s",
                self._bucket, full_key, self._client.meta.endpoint_url)
            raise

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        self._client.put_object(Bucket=self._bucket, Key=self._full(key),
                                Body=data, ContentType=content_type)

    def presign_get(self, key: str, ttl_seconds: int = 24 * 3600) -> str:
        return self._client.generate_presigned_url(
            "get_object", Params={"Bucket": self._bucket, "Key": self._full(key)},
            ExpiresIn=ttl_seconds)
