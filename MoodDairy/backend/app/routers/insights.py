"""Routes for multimodal diary insights and growth portraits."""

from __future__ import annotations

from datetime import date as date_type
from typing import Optional

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.schemas import DiaryMultimodalInsightResponse, ErrorResponse, GrowthPortraitResponse
from app.security.deps import AuthenticatedUserId
from app.services.multimodal_insight_service import MultimodalInsightService
from app.utils.error_handler import ValidationError as AppValidationError

router = APIRouter(prefix="/insights", tags=["insights"])


@router.get(
    "/diaries/{diary_id}",
    response_model=DiaryMultimodalInsightResponse,
    summary="Get multimodal insight for one diary",
    responses={
        200: {"description": "Insight loaded successfully"},
        404: {"model": ErrorResponse, "description": "Insight or diary not found"},
    },
)
async def get_diary_multimodal_insight(
    diary_id: int,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
):
    service = MultimodalInsightService(db)
    return await service.get_diary_insight(diary_id, user_id=current_user_id)


@router.post(
    "/diaries/{diary_id}/analyze",
    response_model=DiaryMultimodalInsightResponse,
    status_code=status.HTTP_200_OK,
    summary="Analyze or refresh one diary insight",
    responses={
        200: {"description": "Insight generated successfully"},
        404: {"model": ErrorResponse, "description": "Diary not found"},
    },
)
async def analyze_diary_multimodal_insight(
    diary_id: int,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
):
    service = MultimodalInsightService(db)
    result = await service.analyze_diary(diary_id, user_id=current_user_id)
    await db.commit()
    return result


@router.get(
    "/growth-portrait",
    response_model=GrowthPortraitResponse,
    summary="Get longitudinal growth portrait",
    responses={
        200: {"description": "Growth portrait loaded successfully"},
        400: {"model": ErrorResponse, "description": "Invalid date range"},
    },
)
async def get_growth_portrait(
    current_user_id: AuthenticatedUserId,
    days: int = 30,
    end_date: Optional[str] = None,
    db: AsyncSession = Depends(get_db),
):
    if days < 7 or days > 365:
        raise AppValidationError("days must be between 7 and 365", details={"days": days})

    parsed_end_date = None
    if end_date:
        try:
            parsed_end_date = date_type.fromisoformat(end_date)
        except ValueError as exc:
            raise AppValidationError("end_date must use YYYY-MM-DD format", details={"end_date": end_date}) from exc

    service = MultimodalInsightService(db)
    result = await service.get_growth_portrait(
        user_id=current_user_id,
        days=days,
        end_date=parsed_end_date,
    )
    await db.commit()
    return result
