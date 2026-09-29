"""Routes for diary text search that must win over /diaries/{date}."""

from __future__ import annotations

from typing import List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.schemas import DiaryResponse, ErrorResponse
from app.services.diary_service import DiaryService
from app.services.diary_embedding_service import DiaryEmbeddingService
from app.security.deps import EffectiveUserId
from app.utils.error_handler import ValidationError as AppValidationError

router = APIRouter(prefix="/diaries", tags=["diaries"])


class VectorSearchResult(BaseModel):
    diary_id: int
    score: float
    metadata: dict


class VectorSearchResponse(BaseModel):
    results: List[VectorSearchResult]
    total: int


@router.get(
    "/search",
    response_model=List[DiaryResponse],
    summary="Search diaries by keyword",
    description="Search diary title/content/text blocks by keyword.",
    responses={
        200: {"description": "Search results returned"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def search_diaries(
    user_id: EffectiveUserId,
    query: str,
    limit: int = 30,
    offset: int = 0,
    db: AsyncSession = Depends(get_db),
):
    normalized = query.strip()
    if not normalized:
        raise AppValidationError("query must not be blank", details={"query": query})

    if limit < 1 or limit > 100:
        raise AppValidationError(
            "limit must be between 1 and 100",
            details={"limit": limit, "valid_range": "1-100"},
        )

    if offset < 0:
        raise AppValidationError("offset must be >= 0", details={"offset": offset})

    service = DiaryService(db)
    diaries = await service.search_diaries_by_text(
        user_id=user_id,
        query=normalized,
        limit=limit,
        offset=offset,
    )
    return [DiaryResponse.model_validate(item) for item in diaries]


@router.get(
    "/vector-search",
    response_model=VectorSearchResponse,
    summary="Search diaries by vector similarity",
    description="Search diaries using Doubao embedding + FAISS cosine similarity.",
    responses={
        200: {"description": "Search results returned"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def vector_search_diaries(
    user_id: EffectiveUserId,
    query: str,
    limit: int = 10,
    db: AsyncSession = Depends(get_db),
):
    """
    Search diaries using vector similarity search.

    - Generates an embedding for the query text using Doubao API.
    - Searches the per-user FAISS index using cosine similarity.
    - Returns diary IDs with similarity scores and metadata.
    """
    normalized = query.strip()
    if not normalized:
        raise AppValidationError("query must not be blank", details={"query": query})

    if limit < 1 or limit > 100:
        raise AppValidationError(
            "limit must be between 1 and 100",
            details={"limit": limit, "valid_range": "1-100"},
        )

    embedding_service = DiaryEmbeddingService(db)
    vector_results = await embedding_service.search_by_embedding(
        user_id=user_id,
        query_text=normalized,
        top_k=limit,
    )

    results = [VectorSearchResult(**r) for r in vector_results]
    return VectorSearchResponse(results=results, total=len(results))


@router.post(
    "/vector-search",
    response_model=VectorSearchResponse,
    summary="Hybrid search diaries (vector + keyword)",
    description="Combined vector similarity and keyword search for better results.",
    responses={
        200: {"description": "Search results returned"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def hybrid_search_diaries(
    user_id: EffectiveUserId,
    query: str,
    limit: int = 10,
    offset: int = 0,
    db: AsyncSession = Depends(get_db),
):
    """
    Hybrid search combining vector similarity and keyword matching.

    1. Vector search: generate query embedding, search FAISS for similar diaries.
    2. Keyword search: SQL ILIKE search on title/content/text blocks.
    3. Merge and rerank results by combined score.
    """
    normalized = query.strip()
    if not normalized:
        raise AppValidationError("query must not be blank", details={"query": query})

    if limit < 1 or limit > 100:
        raise AppValidationError(
            "limit must be between 1 and 100",
            details={"limit": limit, "valid_range": "1-100"},
        )

    if offset < 0:
        raise AppValidationError("offset must be >= 0", details={"offset": offset})

    # Step 1: Vector search
    embedding_service = DiaryEmbeddingService(db)
    vector_results = await embedding_service.search_by_embedding(
        user_id=user_id,
        query_text=normalized,
        top_k=limit * 2,
    )
    vector_diary_ids = {r["diary_id"]: r["score"] for r in vector_results}

    # Step 2: Keyword search
    diary_service = DiaryService(db)
    keyword_diaries = await diary_service.search_diaries_by_text(
        user_id=user_id,
        query=normalized,
        limit=limit * 2,
        offset=0,
    )
    keyword_diary_ids = {d.id: 1.0 for d in keyword_diaries}

    # Step 3: Merge and rerank
    all_diary_ids = set(vector_diary_ids.keys()) | set(keyword_diary_ids.keys())
    merged: List[tuple] = []
    for diary_id in all_diary_ids:
        v_score = vector_diary_ids.get(diary_id, 0.0)
        k_score = 1.0 if diary_id in keyword_diary_ids else 0.0
        # Combined score: weighted average (vector 70%, keyword 30%)
        combined = v_score * 0.7 + k_score * 0.3
        merged.append((diary_id, combined, v_score))

    merged.sort(key=lambda x: x[1], reverse=True)
    final_ids = [diary_id for diary_id, _, _ in merged[offset:offset + limit]]

    if not final_ids:
        return VectorSearchResponse(results=[], total=0)

    # Step 4: Fetch diary details for final results
    fetched_diaries = []
    for diary_id in final_ids:
        diary = await diary_service.get_diary_by_id(diary_id, load_media=True)
        if diary:
            fetched_diaries.append(diary)

    # Build results with combined scores
    id_to_score = {diary_id: score for diary_id, score, _ in merged}
    results = []
    for diary in fetched_diaries:
        results.append(
            VectorSearchResult(
                diary_id=diary.id,
                score=round(id_to_score.get(diary.id, 0.0), 4),
                metadata={
                    "diary_date": str(diary.diary_date) if diary.diary_date else None,
                    "title": diary.title or "",
                    "mood_type": diary.mood_type or "",
                    "mood_score": diary.mood_score,
                    "weather": diary.weather or "",
                    "location": diary.location or "",
                    "created_at": diary.created_at.isoformat() if diary.created_at else None,
                },
            )
        )

    return VectorSearchResponse(results=results, total=len(results))

