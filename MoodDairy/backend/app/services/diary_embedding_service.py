"""
Diary embedding service.

Handles embedding generation for diary entries using Doubao multimodal API,
and stores/retrieves vectors from the per-user FAISS store.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload, joinedload

from app.models.database import Diary, DiaryMedia
from app.services.doubao_embedding_service import get_doubao_embedding_service, DoubaoEmbeddingService
from app.services.diary_faiss_store import get_diary_faiss_store, DiaryFAISSStore

logger = logging.getLogger(__name__)


def _build_diary_text(diary: Diary) -> str:
    """
    Build a combined text representation of a diary for embedding.

    Includes: title, content, text-type media blocks, voice transcriptions.
    """
    parts: List[str] = []

    if diary.title:
        parts.append(f"标题: {diary.title}")

    if diary.content:
        parts.append(f"正文: {diary.content}")

    if diary.weather:
        parts.append(f"天气: {diary.weather}")

    if diary.location:
        parts.append(f"地点: {diary.location}")

    if diary.mood_type:
        parts.append(f"心情: {diary.mood_type}")

    # Append text-type media blocks
    if diary.media_items:
        for media in sorted(diary.media_items, key=lambda m: m.sort_order or 0):
            if media.media_type == "text" and media.content:
                parts.append(f"文本块: {media.content}")

    # Append voice transcription text
    if hasattr(diary, "voice_transcriptions") and diary.voice_transcriptions:
        for vt in diary.voice_transcriptions:
            if vt.processed_text:
                parts.append(f"语音转写: {vt.processed_text}")
            elif vt.original_text:
                parts.append(f"语音转写: {vt.original_text}")

    return "\n".join(parts)


class DiaryEmbeddingService:
    """
    Service for generating and managing diary embeddings.

    1. Builds a combined text representation of a diary.
    2. Calls Doubao multimodal embedding API.
    3. Stores the vector in the per-user FAISS index.
    4. Updates the diary's embedding_status in the database.
    """

    def __init__(
        self,
        db: AsyncSession,
        embedding_service: Optional[DoubaoEmbeddingService] = None,
        faiss_store: Optional[DiaryFAISSStore] = None,
    ):
        self.db = db
        self.embedding_service = embedding_service or get_doubao_embedding_service()
        self.faiss_store = faiss_store or get_diary_faiss_store()

    async def _update_status(self, diary_id: int, status: str) -> None:
        """Update the embedding_status field of a diary."""
        try:
            await self.db.execute(
                update(Diary)
                .where(Diary.id == diary_id)
                .values(embedding_status=status)
            )
            await self.db.commit()
        except Exception as exc:
            logger.error("Failed to update embedding_status for diary %s: %s", diary_id, exc)
            await self.db.rollback()

    async def generate_embedding(self, diary_id: int, force: bool = False) -> bool:
        """
        Generate and store an embedding for a single diary.

        Args:
            diary_id: the diary ID
            force: if True, always regenerate even if already succeeded

        Returns:
            True if embedding was generated and stored successfully.
        """
        await self._update_status(diary_id, "processing")

        try:
            stmt = (
                select(Diary)
                .options(
                    selectinload(Diary.media_items),
                    # Load voice transcriptions
                    joinedload(Diary.voice_transcriptions),
                )
                .where(Diary.id == diary_id)
            )
            result = await self.db.execute(stmt)
            diary = result.scalar_one_or_none()

            if diary is None:
                logger.warning("Diary %s not found for embedding", diary_id)
                await self._update_status(diary_id, "failed")
                return False

            # Skip if already succeeded and not forcing
            if not force and diary.embedding_status == "succeeded":
                logger.info("Diary %s already embedded, skipping (use force=True to re-embed)", diary_id)
                return True

            # Build text content
            text = _build_diary_text(diary)
            if not text.strip():
                logger.warning("Diary %s has no text content to embed", diary_id)
                await self._update_status(diary_id, "failed")
                return False

            # Generate embedding via Doubao API
            embedding = await self.embedding_service.embed_text(text)

            # Check if embedding is all zeros (API failed)
            if all(v == 0.0 for v in embedding):
                logger.error("Doubao embedding returned zero vector for diary %s", diary_id)
                await self._update_status(diary_id, "failed")
                return False

            # Build metadata
            metadata: Dict[str, Any] = {
                "diary_date": str(diary.diary_date) if diary.diary_date else None,
                "title": diary.title or "",
                "mood_type": diary.mood_type or "",
                "mood_score": diary.mood_score,
                "weather": diary.weather or "",
                "location": diary.location or "",
                "word_count": diary.word_count or 0,
                "created_at": diary.created_at.isoformat() if diary.created_at else None,
            }

            # Handle image media - we include their URLs in metadata for future multi-modal embedding
            image_urls: List[str] = []
            if diary.media_items:
                for media in diary.media_items:
                    if media.media_type == "image" and (media.media_url or media.thumbnail_url):
                        url = media.media_url or media.thumbnail_url
                        if url:
                            image_urls.append(url)
            if image_urls:
                metadata["image_urls"] = image_urls

            # Store in FAISS
            self.faiss_store.add_diary_embedding(
                user_id=diary.user_id,
                diary_id=diary_id,
                embedding=embedding,
                metadata=metadata,
            )

            await self._update_status(diary_id, "succeeded")
            logger.info("Successfully embedded diary %s for user %s", diary_id, diary.user_id)
            return True

        except Exception as exc:
            logger.error("Failed to generate embedding for diary %s: %s", diary_id, exc)
            await self._update_status(diary_id, "failed")
            return False

    async def delete_embedding(self, diary_id: int, user_id: int) -> bool:
        """Remove a diary's embedding from the FAISS index."""
        try:
            result = self.faiss_store.remove_diary_embedding(user_id=user_id, diary_id=diary_id)
            if result:
                logger.info("Removed embedding for diary %s from user %s index", diary_id, user_id)
            return result
        except Exception as exc:
            logger.error("Failed to delete embedding for diary %s: %s", diary_id, exc)
            return False

    @staticmethod
    def remove_embedding_sync(diary_id: int, user_id: int) -> bool:
        """Synchronously remove a diary's embedding from the FAISS index."""
        try:
            store = get_diary_faiss_store()
            result = store.remove_diary_embedding(user_id=user_id, diary_id=diary_id)
            if result:
                logger.info("Removed embedding for diary %s from user %s index", diary_id, user_id)
            return result
        except Exception as exc:
            logger.error("Failed to delete embedding for diary %s: %s", diary_id, exc)
            return False

    async def search_by_embedding(
        self,
        user_id: int,
        query_text: str,
        top_k: int = 10,
    ) -> List[Dict[str, Any]]:
        """
        Search diaries by text query using cosine similarity.

        Args:
            user_id: user ID (search is scoped to this user)
            query_text: natural language query string
            top_k: number of results to return

        Returns:
            List of dicts with keys: diary_id, score, metadata
        """
        try:
            # Generate query embedding
            query_embedding = await self.embedding_service.embed_text(query_text)

            if all(v == 0.0 for v in query_embedding):
                logger.error("Doubao embedding returned zero vector for search query")
                return []

            # Search in FAISS
            results = self.faiss_store.search(
                user_id=user_id,
                query_embedding=query_embedding,
                top_k=top_k,
            )

            output: List[Dict[str, Any]] = []
            for diary_id, score, metadata in results:
                output.append({
                    "diary_id": diary_id,
                    "score": round(score, 4),
                    "metadata": metadata,
                })

            return output

        except Exception as exc:
            logger.error("Vector search failed for user %s: %s", user_id, exc)
            return []

    async def search_by_embedding_async(
        self,
        user_id: int,
        query_text: str,
        top_k: int = 10,
    ) -> List[Dict[str, Any]]:
        """Async wrapper for search_by_embedding (usable in sync contexts)."""
        return await self.search_by_embedding(user_id, query_text, top_k)
