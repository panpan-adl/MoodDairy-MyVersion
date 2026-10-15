"""FAISS-backed vector store utilities."""

from __future__ import annotations

import json
import logging
import os
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

try:
    import faiss  # type: ignore[import-not-found]
except ImportError as exc:  # pragma: no cover - depends on local environment
    faiss = None
    _FAISS_IMPORT_ERROR = exc
else:
    _FAISS_IMPORT_ERROR = None

logger = logging.getLogger(__name__)

DEFAULT_INDEX_PATH = os.path.join(os.path.dirname(__file__), "../data/faiss_index")


def _require_faiss() -> None:
    if faiss is None:
        raise RuntimeError(
            "FAISS is not installed. Install it with `pip install faiss-cpu` "
            "to enable vector search."
        ) from _FAISS_IMPORT_ERROR


class FAISSStore:
    """Simple FAISS vector store with persisted metadata."""

    def __init__(self, dimension: int = 2048, index_path: str = DEFAULT_INDEX_PATH):
        self.dimension = dimension
        self.index_path = index_path
        self.index: Optional[Any] = None
        self.documents: List[Dict[str, Any]] = []
        os.makedirs(os.path.dirname(index_path) if os.path.dirname(index_path) else ".", exist_ok=True)
        logger.info("FAISSStore initialized: dimension=%s path=%s", dimension, index_path)

    def _create_index(self) -> Any:
        _require_faiss()
        # IndexFlatL2 为精确检索：本机 faiss 的 HNSW 实现存在原生段错误，
        # 且个人日记应用的向量规模（百级）下 Flat 检索性能足够且结果更准
        index = faiss.IndexFlatL2(self.dimension)
        logger.info("Created FlatL2 FAISS index (exact search), dimension=%s", self.dimension)
        return index

    def add_documents(self, embeddings: List[List[float]], documents: List[Dict[str, Any]]) -> None:
        if len(embeddings) != len(documents):
            raise ValueError("embeddings and documents must have the same length")
        if not embeddings:
            logger.warning("No documents to add")
            return
        if self.index is None:
            self.dimension = len(embeddings[0])
            self.index = self._create_index()

        vectors = np.array(embeddings, dtype=np.float32)
        self.index.add(vectors)
        self.documents.extend(documents)
        logger.info("Added %s documents to FAISS index", len(documents))

    def search(self, query_embedding: List[float], top_k: int = 5) -> List[Tuple[Dict[str, Any], float]]:
        if self.index is None or self.index.ntotal == 0:
            logger.warning("FAISS index is empty")
            return []

        query_vector = np.array([query_embedding], dtype=np.float32)
        distances, indices = self.index.search(query_vector, min(top_k, self.index.ntotal))

        results: List[Tuple[Dict[str, Any], float]] = []
        for distance, idx in zip(distances[0], indices[0]):
            if 0 <= idx < len(self.documents):
                similarity = 1.0 / (1.0 + float(distance))
                results.append((self.documents[idx], similarity))
        return results

    def save(self) -> None:
        if self.index is None:
            logger.warning("No FAISS index to save")
            return

        _require_faiss()
        index_file = f"{self.index_path}.index"
        docs_file = f"{self.index_path}.json"
        faiss.write_index(self.index, index_file)
        with open(docs_file, "w", encoding="utf-8") as f:
            json.dump({"dimension": self.dimension, "documents": self.documents}, f, ensure_ascii=False, indent=2)
        logger.info("Saved FAISS index to %s", index_file)

    def load(self) -> bool:
        index_file = f"{self.index_path}.index"
        docs_file = f"{self.index_path}.json"

        if not os.path.exists(index_file) or not os.path.exists(docs_file):
            logger.info("FAISS index files do not exist yet")
            return False

        try:
            _require_faiss()
            self.index = faiss.read_index(index_file)
            with open(docs_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.dimension = data.get("dimension", 2048)
            self.documents = data.get("documents", [])
            logger.info("Loaded FAISS index from %s", index_file)
            return True
        except Exception as exc:
            logger.error("Failed to load FAISS index: %s", exc)
            return False

    def clear(self) -> None:
        self.index = None
        self.documents = []
        logger.info("Cleared FAISS index")

    @property
    def count(self) -> int:
        return len(self.documents)


_faiss_store: Optional[FAISSStore] = None


def get_faiss_store() -> FAISSStore:
    global _faiss_store
    if _faiss_store is None:
        _faiss_store = FAISSStore()
        _faiss_store.load()
    return _faiss_store
