"""产物采集与上限校验（§4.7 出口契约）：manifest.json 优先，缺省按后缀推断 type。"""

from __future__ import annotations

import json
from typing import Optional

from ..domain import ArtifactFile

MANIFEST_NAME = "manifest.json"

# 后缀 → 产物类型（manifest 缺失时的推断表）
SUFFIX_TYPES = {
    "mp4": "video", "mov": "video", "avi": "video", "webm": "video",
    "jpg": "image", "jpeg": "image", "png": "image", "gif": "image", "webp": "image", "bmp": "image",
    "mp3": "audio", "wav": "audio", "aac": "audio", "m4a": "audio", "flac": "audio",
    "pdf": "document", "doc": "document", "docx": "document", "xls": "document",
    "xlsx": "document", "ppt": "document", "pptx": "document", "txt": "document",
    "csv": "document", "md": "document",
    "json": "json", "html": "html",
}

# 后缀 → MIME（V2.1：落库 content_type；gateway 产物预览按 contentType/type 双口径判定）
CONTENT_TYPES = {
    "json": "application/json", "html": "text/html", "htm": "text/html",
    "md": "text/markdown", "txt": "text/plain", "csv": "text/csv",
    "pdf": "application/pdf",
    "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif",
    "webp": "image/webp", "svg": "image/svg+xml",
    "mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
    "mp3": "audio/mpeg", "wav": "audio/wav", "m4a": "audio/mp4",
}


def infer_type(filename: str) -> str:
    suffix = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    return SUFFIX_TYPES.get(suffix, "other")


def infer_content_type(filename: str, declared_type: str = "") -> str:
    """产物 MIME 推断：后缀表优先；声明 type 可识别时兜底（json→application/json 等）。"""
    suffix = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    mime = CONTENT_TYPES.get(suffix)
    if mime:
        return mime
    return {
        "json": "application/json", "html": "text/html", "markdown": "text/markdown",
        "text": "text/plain", "video": "video/mp4", "image": "image/png",
        "audio": "audio/mpeg",
    }.get(declared_type.lower(), "application/octet-stream")


def collect_artifacts(files: dict[str, bytes]) -> list[ArtifactFile]:
    """从工作区 artifacts/ 文件集构造产物列表：manifest 声明优先，其余按后缀推断。

    files: {相对路径: 内容}（FakeSandbox 与 K8sSandbox 均输出该形态）
    """
    manifest: dict = {}
    raw_manifest = files.get(MANIFEST_NAME)
    if raw_manifest:
        try:
            manifest = json.loads(raw_manifest.decode())
        except (ValueError, UnicodeDecodeError):
            manifest = {}
    # manifest 兼容两种形态：{files: [{filename,type,meta}]} 或直接数组
    declared = manifest.get("files") if isinstance(manifest, dict) else manifest
    declared_map: dict[str, dict] = {}
    if isinstance(declared, list):
        for item in declared:
            if isinstance(item, dict) and item.get("filename"):
                declared_map[item["filename"]] = item

    artifacts: list[ArtifactFile] = []
    for filename, content in sorted(files.items()):
        if filename == MANIFEST_NAME:
            continue  # manifest 本身不是产物
        declaration = declared_map.get(filename, {})
        artifact_type = str(declaration.get("type") or infer_type(filename))
        artifacts.append(ArtifactFile(
            filename=filename,
            content=content,
            type=artifact_type,
            content_type=infer_content_type(filename, artifact_type),
            meta=dict(declaration.get("meta") or {}),
        ))
    return artifacts


def over_limit(files: list[ArtifactFile], output_config: dict,
               max_file_bytes: int, max_total_bytes: int, default_max_count: int) -> Optional[str]:
    """产物上限（§4.7）：单文件 ≤500MB、总量 ≤2GB、数量 ≤ maxCount×2（countable=false ≤4）。

    返回超限说明；None = 未超限。
    """
    countable = bool(output_config.get("countable"))
    max_count = (output_config.get("maxCount") or 0) * 2 if countable else default_max_count
    if len(files) > max_count:
        return f"OUTPUT_LIMIT: 文件数 {len(files)} 超过上限 {max_count}"
    total = sum(file.size for file in files)
    if total > max_total_bytes:
        return f"OUTPUT_LIMIT: 总量 {total} 字节超过上限 {max_total_bytes}"
    for file in files:
        if file.size > max_file_bytes:
            return f"OUTPUT_LIMIT: 单文件 {file.filename} {file.size} 字节超过上限 {max_file_bytes}"
    return None
