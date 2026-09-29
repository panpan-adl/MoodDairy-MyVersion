"""Embedding service backed by Doubao (Volcano Engine Ark API)."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import List, Optional

from dotenv import load_dotenv

logger = logging.getLogger(__name__)

load_dotenv()

DOUBAO_EMBEDDING_DIMENSION = int(os.getenv("DOUBAO_EMBEDDING_DIMENSION", "2048"))


class EmbeddingService:
    """Generate embeddings via Doubao multimodal embedding API."""

    def __init__(self, dimension: int = DOUBAO_EMBEDDING_DIMENSION):
        self._dimension: int = dimension
        self._doubao = None
        self._initialized = False
        self._last_init_check = 0.0
        self._init_ttl = 300.0
        logger.info("EmbeddingService initialized: Doubao embedding, dimension=%s", dimension)

    def _get_doubao(self):
        now = time.monotonic()
        if self._initialized and (now - self._last_init_check) < self._init_ttl:
            return self._doubao

        from app.services.doubao_embedding_service import get_doubao_embedding_service

        self._doubao = get_doubao_embedding_service()
        self._initialized = True
        self._last_init_check = now
        return self._doubao

    async def is_available(self, force_refresh: bool = False) -> bool:
        try:
            client = self._get_doubao()
            if client is None:
                return False
            await client.embed_text("health check")
            return True
        except Exception as exc:
            logger.warning("Doubao embedding service unavailable: %s", exc)
            return False

    async def embed_text(self, text: str) -> List[float]:
        try:
            client = self._get_doubao()
            if client is None:
                return [0.0] * self.dimension
            return await client.embed_text(text)
        except Exception as exc:
            logger.error("Failed to embed text: %s", exc)
            return [0.0] * self.dimension

    async def embed_texts(self, texts: List[str], batch_size: int = 1) -> List[List[float]]:
        del batch_size

        if not texts:
            return []

        embeddings: List[List[float]] = []
        for index, text in enumerate(texts):
            try:
                embedding = await self.embed_text(text)
                embeddings.append(embedding)
            except Exception as exc:
                logger.error("Embedding failed for text %s: %s", index, exc)
                embeddings.append([0.0] * self.dimension)

            if (index + 1) % 5 == 0 or (index + 1) == len(texts):
                logger.info("Embedding progress: %s/%s", index + 1, len(texts))

            await asyncio.sleep(0.05)

        return embeddings

    @property
    def dimension(self) -> int:
        return self._dimension

    async def get_dimension(self) -> int:
        return self._dimension


_embedding_service: Optional[EmbeddingService] = None


def get_embedding_service() -> EmbeddingService:
    """Return the process-wide embedding service instance."""

    global _embedding_service
    if _embedding_service is None:
        _embedding_service = EmbeddingService()
    return _embedding_service
