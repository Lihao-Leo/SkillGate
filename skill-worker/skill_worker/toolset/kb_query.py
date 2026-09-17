"""kb_query（§4.5）：Milvus RAG 检索（collection 按租户前缀物理隔离，§3.6）。"""

from __future__ import annotations

from typing import Protocol


class KbPort(Protocol):
    def search(self, collection: str, query: str, top_k: int) -> list[dict]:
        """[{text, score, metadata}]"""
        ...


class MilvusKb:
    """生产：pymilvus（embedding 经 LiteLLM embedding_alias，由调用方注入 embed 函数）。"""

    def __init__(self, kb_port: KbPort, embed_fn):
        self._kb = kb_port
        self._embed = embed_fn

    def query(self, tenant_id: str, kb_id: str, query: str, top_k: int = 5) -> list[dict]:
        collection = f"{tenant_id}__{kb_id}"  # 租户前缀 = 物理隔离边界
        vector = self._embed(query)
        return self._kb.search(collection, vector, top_k)
