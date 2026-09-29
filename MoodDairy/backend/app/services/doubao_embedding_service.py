"""
Doubao multimodal embedding service.
Uses Volcano Engine Ark API to generate embeddings for text and images.

IMPORTANT: doubao-embedding-vision-251215 returns 2048-dimensional vectors.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import os
import re
from typing import Any, Dict, List, Optional, Union

from dotenv import load_dotenv

logger = logging.getLogger(__name__)

load_dotenv()

ARK_API_KEY = os.getenv("ARK_API_KEY", os.getenv("DOUBAO_API_KEY", ""))
EMBEDDING_MODEL = os.getenv("DOUBAO_EMBEDDING_MODEL", "doubao-embedding-vision-251215")
# doubao-embedding-vision-251215 outputs 2048-dimensional vectors
EMBEDDING_DIMENSION = int(os.getenv("DOUBAO_EMBEDDING_DIMENSION", "2048"))

IMAGE_URL_PATTERN = re.compile(r"^https?://", re.IGNORECASE)


def _load_image_as_base64(image_path: str) -> str:
    """Load image file and encode as base64 data URL."""
    with open(image_path, "rb") as f:
        data = f.read()
    b64 = base64.b64encode(data).decode("utf-8")
    ext = os.path.splitext(image_path)[1].lower().lstrip(".")
    mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}.get(
        ext, "application/octet-stream"
    )
    return f"data:{mime};base64,{b64}"


class DoubaoEmbeddingService:
    """
    Multimodal embedding service backed by Doubao (Volcano Engine Ark API).

    Supports text and image inputs. For images, accepts either URLs or local
    file paths (converted to base64 data URLs internally).
    """

    def __init__(
        self,
        api_key: str = ARK_API_KEY,
        model: str = EMBEDDING_MODEL,
        dimension: int = EMBEDDING_DIMENSION,
    ):
        self.api_key = api_key
        self.model = model
        self.dimension = dimension
        self._client = None
        self._initialized = False
        logger.info(
            "DoubaoEmbeddingService initialized: model=%s dimension=%s",
            model,
            dimension,
        )

    def _get_client(self) -> Any:
        if not self._initialized:
            try:
                from volcenginesdkarkruntime import Ark

                self._client = Ark(api_key=self.api_key)
                self._initialized = True
            except ImportError:
                logger.error(
                    "volcenginesdkarkruntime not installed. "
                    "Run: pip install volcenginesdkarkruntime"
                )
                raise
        return self._client

    def _normalize_input_item(
        self, item: Union[str, Dict[str, Any]]
    ) -> Dict[str, Any]:
        """Convert a simple string or dict into the API's expected format."""
        if isinstance(item, str):
            if IMAGE_URL_PATTERN.match(item):
                return {"type": "image_url", "image_url": {"url": item}}
            else:
                return {"type": "text", "text": item}
        return item

    async def embed_text(self, text: str) -> List[float]:
        """
        Embed a single text input.

        Returns a normalized vector of self.dimension.
        """
        try:
            client = self._get_client()
        except ImportError:
            return [0.0] * self.dimension

        def _call():
            resp = client.multimodal_embeddings.create(
                model=self.model,
                input=[{"type": "text", "text": text}],
            )
            # resp.data is a single MultimodalEmbedding object (not a list)
            vec = resp.data.embedding
            return self._normalize_vector(vec)

        return await asyncio.to_thread(_call)

    async def embed_image_url(self, image_url: str) -> List[float]:
        """
        Embed an image accessible via URL.

        Returns a normalized vector of self.dimension.
        """
        try:
            client = self._get_client()
        except ImportError:
            return [0.0] * self.dimension

        def _call():
            resp = client.multimodal_embeddings.create(
                model=self.model,
                input=[{"type": "image_url", "image_url": {"url": image_url}}],
            )
            # resp.data is a single MultimodalEmbedding object (not a list)
            vec = resp.data.embedding
            return self._normalize_vector(vec)

        return await asyncio.to_thread(_call)

    async def embed_image_file(self, image_path: str) -> List[float]:
        """Embed a local image file (converted to base64 internally)."""
        try:
            data_url = _load_image_as_base64(image_path)
            return await self.embed_image_url(data_url)
        except Exception as exc:
            logger.warning("Failed to embed image file %s: %s", image_path, exc)
            return [0.0] * self.dimension

    async def embed_multimodal(
        self,
        items: List[Union[str, Dict[str, Any]]],
    ) -> List[List[float]]:
        """
        Generate embeddings for a list of mixed text/image inputs.

        Args:
            items: List of strings (text or image URL/path) or dicts with
                   {"type": "text"|"image_url", "text": ..., "image_url": ...}

        Returns:
            List of normalized embedding vectors, one per input item.
        """
        if not items:
            return []

        try:
            client = self._get_client()
        except ImportError:
            return [[0.0] * self.dimension for _ in items]

        # doubao-embedding-vision-251215: call one at a time since
        # the API returns a single embedding per call regardless of input count
        results: List[List[float]] = []
        for item in items:
            normalized = self._normalize_input_item(item)

            def _call(n=normalized):
                resp = client.multimodal_embeddings.create(
                    model=self.model,
                    input=[n],
                )
                # resp.data is a single MultimodalEmbedding object
                vec = resp.data.embedding
                return self._normalize_vector(vec)

            results.append(await asyncio.to_thread(_call))
            await asyncio.sleep(0.05)

        return results

    async def embed_texts(self, texts: List[str]) -> List[List[float]]:
        """Embed multiple texts."""
        if not texts:
            return []
        return await self.embed_multimodal([{"type": "text", "text": t} for t in texts])

    def _normalize_vector(self, vec: List[float]) -> List[float]:
        """L2-normalize a vector so cosine similarity becomes inner product."""
        import math

        norm = math.sqrt(sum(v * v for v in vec))
        if norm < 1e-10:
            return [0.0] * len(vec)
        return [v / norm for v in vec]


_doubao_embedding_service: Optional[DoubaoEmbeddingService] = None


def get_doubao_embedding_service() -> DoubaoEmbeddingService:
    """Return the process-wide Doubao embedding service singleton."""
    global _doubao_embedding_service
    if _doubao_embedding_service is None:
        _doubao_embedding_service = DoubaoEmbeddingService()
    return _doubao_embedding_service
