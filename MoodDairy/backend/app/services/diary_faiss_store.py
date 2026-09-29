"""
Diary FAISS vector store.
Per-user FAISS indexes stored locally, with cosine similarity search.
Each user gets their own index file so that embedding search is user-scoped.
"""

from __future__ import annotations

import json
import logging
import math
import os
import shutil
import threading
from pathlib import Path
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

DEFAULT_INDEX_DIR = os.path.join(os.path.dirname(__file__), "../../data/diary_embeddings")


def _require_faiss() -> None:
    if faiss is None:
        raise RuntimeError(
            "FAISS is not installed. Install it with `pip install faiss-cpu` "
            "to enable diary vector search."
        ) from _FAISS_IMPORT_ERROR


class DiaryFAISSStore:
    """
    Per-user FAISS vector store for diary embeddings.

    Each user has their own index stored at:
        {INDEX_DIR}/{user_id}/index.faiss  +  metadata.json

    Vectors are stored L2-normalized so that inner product == cosine similarity.
    """

    def __init__(
        self,
        index_dir: str = DEFAULT_INDEX_DIR,
        dimension: int = 2048,
    ):
        self.index_dir = Path(index_dir)
        self.dimension = dimension
        self._user_indexes: Dict[int, Any] = {}
        self._user_documents: Dict[int, List[Dict[str, Any]]] = {}
        self._user_locks: Dict[int, threading.Lock] = {}
        self._global_lock = threading.Lock()

        self.index_dir.mkdir(parents=True, exist_ok=True)
        logger.info("DiaryFAISSStore initialized: dir=%s dim=%s", index_dir, dimension)

    def _user_dir(self, user_id: int) -> Path:
        return self.index_dir / str(user_id)

    def _user_index_file(self, user_id: int) -> Path:
        return self._user_dir(user_id) / "index.faiss"

    def _user_meta_file(self, user_id: int) -> Path:
        return self._user_dir(user_id) / "metadata.json"

    def _get_lock(self, user_id: int) -> threading.Lock:
        with self._global_lock:
            if user_id not in self._user_locks:
                self._user_locks[user_id] = threading.Lock()
            return self._user_locks[user_id]

    def _get_index(self, user_id: int) -> Any:
        _require_faiss()
        if user_id not in self._user_indexes:
            index_file = self._user_index_file(user_id)
            if index_file.exists():
                try:
                    self._user_indexes[user_id] = faiss.read_index(str(index_file))
                    logger.info("Loaded index for user %s", user_id)
                except Exception as exc:
                    logger.warning("Failed to load index for user %s: %s; creating new", user_id, exc)
                    self._user_indexes[user_id] = self._create_index()
            else:
                self._user_indexes[user_id] = self._create_index()
        return self._user_indexes[user_id]

    def _get_documents(self, user_id: int) -> List[Dict[str, Any]]:
        if user_id not in self._user_documents:
            meta_file = self._user_meta_file(user_id)
            if meta_file.exists():
                try:
                    with open(meta_file, "r", encoding="utf-8") as f:
                        data = json.load(f)
                    self._user_documents[user_id] = data.get("documents", [])
                    logger.info("Loaded %s docs for user %s", len(self._user_documents[user_id]), user_id)
                except Exception as exc:
                    logger.warning("Failed to load metadata for user %s: %s", user_id, exc)
                    self._user_documents[user_id] = []
            else:
                self._user_documents[user_id] = []
        return self._user_documents[user_id]

    def _create_index(self) -> Any:
        _require_faiss()
        dim = self.dimension
        index = faiss.IndexFlatIP(dim)
        logger.info("Created IndexFlatIP (cosine similarity) dimension=%s", dim)
        return index

    def _normalize(self, vec: List[float]) -> np.ndarray:
        norm = math.sqrt(sum(v * v for v in vec))
        if norm < 1e-10:
            return np.zeros(self.dimension, dtype=np.float32)
        arr = np.array(vec, dtype=np.float32) / norm
        return arr

    def add_diary_embedding(
        self,
        user_id: int,
        diary_id: int,
        embedding: List[float],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        Add or update a diary entry's embedding vector.

        If the diary_id already exists for this user, it is replaced.
        """
        lock = self._get_lock(user_id)
        with lock:
            index = self._get_index(user_id)
            docs = self._get_documents(user_id)

            normalized = self._normalize(embedding).reshape(1, -1)

            existing_idx = None
            for idx, doc in enumerate(docs):
                if doc.get("diary_id") == diary_id:
                    existing_idx = idx
                    break

            if existing_idx is not None:
                docs[existing_idx] = {
                    "diary_id": diary_id,
                    "metadata": metadata or {},
                }
                index.remove_ids(np.array([existing_idx], dtype=np.int64))
                index.add(normalized)
                logger.debug("Replaced embedding for diary_id=%s in user %s index", diary_id, user_id)
            else:
                docs.append({
                    "diary_id": diary_id,
                    "metadata": metadata or {},
                })
                index.add(normalized)
                logger.debug("Added embedding for diary_id=%s to user %s index", diary_id, user_id)

            self._save_user_index(user_id)

    def remove_diary_embedding(self, user_id: int, diary_id: int) -> bool:
        """Remove a diary's embedding from the index. Returns True if found."""
        lock = self._get_lock(user_id)
        with lock:
            docs = self._get_documents(user_id)
            idx_to_remove = None
            for idx, doc in enumerate(docs):
                if doc.get("diary_id") == diary_id:
                    idx_to_remove = idx
                    break

            if idx_to_remove is None:
                return False

            index = self._get_index(user_id)
            index.remove_ids(np.array([idx_to_remove], dtype=np.int64))
            docs.pop(idx_to_remove)
            self._save_user_index(user_id)
            logger.debug("Removed diary_id=%s from user %s index", diary_id, user_id)
            return True

    def search(
        self,
        user_id: int,
        query_embedding: List[float],
        top_k: int = 5,
    ) -> List[Tuple[int, float, Dict[str, Any]]]:
        """
        Search for the most similar diaries using cosine similarity.

        Args:
            user_id: user ID (scope isolation)
            query_embedding: the query vector (will be normalized)
            top_k: number of results to return

        Returns:
            List of (diary_id, cosine_similarity, metadata) tuples, sorted by
            similarity descending.
        """
        lock = self._get_lock(user_id)
        with lock:
            index = self._get_index(user_id)
            docs = self._get_documents(user_id)

            if index.ntotal == 0 or not docs:
                return []

            normalized = self._normalize(query_embedding).reshape(1, -1)
            k = min(top_k, index.ntotal)
            scores, indices = index.search(normalized.astype(np.float32), k)

            results: List[Tuple[int, float, Dict[str, Any]]] = []
            for score, idx in zip(scores[0], indices[0]):
                if 0 <= idx < len(docs):
                    diary_id = docs[idx]["diary_id"]
                    meta = docs[idx].get("metadata", {})
                    results.append((diary_id, float(score), meta))

            return results

    def _save_user_index(self, user_id: int) -> None:
        user_dir = self._user_dir(user_id)
        user_dir.mkdir(parents=True, exist_ok=True)

        _require_faiss()
        index = self._get_index(user_id)
        docs = self._get_documents(user_id)

        faiss.write_index(index, str(self._user_index_file(user_id)))

        with open(self._user_meta_file(user_id), "w", encoding="utf-8") as f:
            json.dump({
                "dimension": self.dimension,
                "documents": docs,
            }, f, ensure_ascii=False, indent=2)

        logger.debug("Saved index for user %s: %s vectors", user_id, index.ntotal)

    def clear_user_index(self, user_id: int) -> None:
        """Delete all embeddings for a user."""
        lock = self._get_lock(user_id)
        with lock:
            if user_id in self._user_indexes:
                del self._user_indexes[user_id]
            if user_id in self._user_documents:
                del self._user_documents[user_id]

            user_dir = self._user_dir(user_id)
            if user_dir.exists():
                shutil.rmtree(user_dir)
                logger.info("Cleared FAISS index for user %s", user_id)

    def get_user_vector_count(self, user_id: int) -> int:
        """Return the number of indexed vectors for a user."""
        lock = self._get_lock(user_id)
        with lock:
            index = self._get_index(user_id)
            return index.ntotal

    def rebuild_index(
        self,
        user_id: int,
        entries: List[Dict[str, Any]],
    ) -> None:
        """
        Rebuild a user's entire index from scratch.

        Args:
            user_id: the user ID
            entries: list of dicts with keys:
                     - diary_id (int)
                     - embedding (List[float])
                     - metadata (Dict, optional)
        """
        lock = self._get_lock(user_id)
        with lock:
            new_index = self._create_index()
            new_docs: List[Dict[str, Any]] = []

            for entry in entries:
                vec = self._normalize(entry["embedding"])
                new_index.add(vec.reshape(1, -1).astype(np.float32))
                new_docs.append({
                    "diary_id": entry["diary_id"],
                    "metadata": entry.get("metadata", {}),
                })

            self._user_indexes[user_id] = new_index
            self._user_documents[user_id] = new_docs
            self._save_user_index(user_id)
            logger.info("Rebuilt index for user %s: %s entries", user_id, len(entries))


_store: Optional[DiaryFAISSStore] = None
_store_lock = threading.Lock()


def get_diary_faiss_store() -> DiaryFAISSStore:
    """Return the process-wide diary FAISS store singleton."""
    global _store
    if _store is None:
        with _store_lock:
            if _store is None:
                dimension = int(os.getenv("DOUBAO_EMBEDDING_DIMENSION", "2048"))
                _store = DiaryFAISSStore(dimension=dimension)
    return _store
