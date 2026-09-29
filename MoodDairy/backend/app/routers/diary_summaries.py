"""Routes for diary summary queries that must win over /diaries/{date}."""

from datetime import date as date_type
from typing import List, Optional

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.schemas import DiarySummarySimple, ErrorResponse
from app.security.deps import EffectiveUserId
from app.utils.error_handler import ValidationError as AppValidationError

router = APIRouter(prefix="/diaries", tags=["diaries"])


@router.get(
    "/summaries",
    response_model=List[DiarySummarySimple],
    summary="Query multiple diary summaries",
    responses={
        200: {"description": "Summary list returned"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def get_summaries(
    user_id: EffectiveUserId,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    keywords: Optional[str] = None,
    emotions: Optional[str] = None,
    limit: int = 10,
    db: AsyncSession = Depends(get_db),
):
    if limit < 1 or limit > 100:
        raise AppValidationError(
            "limit must be between 1 and 100",
            details={"limit": limit, "valid_range": "1-100"},
        )

    from app.services.extraction_service import ExtractionService

    extraction_service = ExtractionService(db)

    keyword_list = keywords.split(",") if keywords else None
    emotion_list = emotions.split(",") if emotions else None
    start_date_obj = None
    end_date_obj = None

    if start_date:
        try:
            start_date_obj = date_type.fromisoformat(start_date)
        except ValueError as exc:
            raise AppValidationError(
                f"Invalid start_date format: {start_date}",
                details={"start_date": start_date, "expected_format": "YYYY-MM-DD"},
            ) from exc

    if end_date:
        try:
            end_date_obj = date_type.fromisoformat(end_date)
        except ValueError as exc:
            raise AppValidationError(
                f"Invalid end_date format: {end_date}",
                details={"end_date": end_date, "expected_format": "YYYY-MM-DD"},
            ) from exc

    summaries = await extraction_service.get_summaries(
        user_id=user_id,
        start_date=start_date_obj,
        end_date=end_date_obj,
        keywords=keyword_list,
        emotions=emotion_list,
        limit=limit,
    )
    return [DiarySummarySimple(**summary) for summary in summaries]
