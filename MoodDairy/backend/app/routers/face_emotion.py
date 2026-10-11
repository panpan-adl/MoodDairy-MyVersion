"""
摄像头表情识别上报路由

接收 App 端本地表情识别结果（情绪标签 + 置信度），写入 emotion_records 表，
供聊天时的情绪编排服务作为表情信号源使用。
"""
import json
import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field, field_validator, model_validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.database import EmotionRecord
from app.models.schemas import ErrorResponse
from app.security.deps import AuthenticatedUserId
from app.services.emotion_orchestration_service import EmotionOrchestrationService
from typing import Any, Dict, Optional, Literal

router = APIRouter(prefix="/face-emotion", tags=["face-emotion"])
logger = logging.getLogger(__name__)

ALLOWED_EMOTIONS = {
    "angry",
    "disgust",
    "fear",
    "happy",
    "sad",
    "surprise",
    "neutral",
}

EMOTION_SCORE_MAP = {
    "happy": 80,
    "neutral": 60,
    "surprise": 55,
    "sad": 35,
    "angry": 30,
    "fear": 30,
    "disgust": 30,
}

SOURCE_TYPE_FACE_CAMERA = "face_camera"


class FaceEmotionReportRequest(BaseModel):
    emotion: str = Field(..., description="表情标签，如 happy/sad/angry 等")
    confidence: float = Field(..., ge=0.0, le=1.0, description="识别置信度，0-1")
    captured_at: datetime = Field(..., description="客户端采集时间")
    sustained_negative_ms: int = Field(default=0, ge=0, le=60_000)
    facial_expression: Optional[Literal["smile", "broad_smile", "delighted_surprise"]] = None

    @model_validator(mode="after")
    def validate_expression(self):
        expected = {"smile": "happy", "broad_smile": "happy", "delighted_surprise": "surprise"}
        if self.facial_expression and expected[self.facial_expression] != self.emotion:
            raise ValueError("表情细分类别与基础表情不一致")
        return self

    @field_validator("emotion")
    @classmethod
    def validate_emotion(cls, value: str) -> str:
        normalized = (value or "").strip().lower()
        if normalized not in ALLOWED_EMOTIONS:
            raise ValueError(
                f"无效的表情标签: {value}，有效值为 {', '.join(sorted(ALLOWED_EMOTIONS))}"
            )
        return normalized

    @field_validator("captured_at")
    @classmethod
    def normalize_captured_at(cls, value: datetime) -> datetime:
        # recorded_at 列为 TIMESTAMP WITHOUT TIME ZONE，全库约定 naive 本地时间
        # （created_at default=datetime.now，聊天侧窗口查询也用 datetime.now）。
        # App 端发送 Instant.toString()（UTC，形如 2026-10-02T01:07:53.382Z），
        # 必须转到服务器本地时间再去掉 tzinfo，否则会与时间窗查询差 8 小时。
        if value.tzinfo is not None:
            value = value.astimezone().replace(tzinfo=None)
        return value


class FaceEmotionReportResponse(BaseModel):
    success: bool
    message: str
    service_bundle: Optional[Dict[str, Any]] = None


@router.post(
    "/report",
    response_model=FaceEmotionReportResponse,
    status_code=status.HTTP_200_OK,
    summary="上报摄像头表情识别结果",
    description="接收 App 端本地表情识别结果（情绪标签 + 置信度），写入情绪记录表。",
    responses={
        200: {"description": "上报成功"},
        401: {"model": ErrorResponse, "description": "未登录或令牌无效"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"},
    },
)
async def report_face_emotion(
    payload: FaceEmotionReportRequest,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
):
    """
    上报摄像头表情识别结果

    Args:
        payload: 表情上报请求体（emotion/confidence/captured_at）
        current_user_id: 当前登录用户ID
        db: 数据库会话

    Returns:
        FaceEmotionReportResponse: 上报结果
    """
    try:
        analysis = json.dumps(
            {
                "confidence": payload.confidence,
                "captured_at": payload.captured_at.isoformat(),
                "facial_expression": payload.facial_expression,
            },
            ensure_ascii=False,
        )
        record = EmotionRecord(
            user_id=current_user_id,
            source_type=SOURCE_TYPE_FACE_CAMERA,
            source_id=None,
            emotion_type=payload.emotion,
            emotion_score=EMOTION_SCORE_MAP[payload.emotion],
            analysis=analysis,
            recorded_at=payload.captured_at,
        )
        db.add(record)
        await db.commit()

        logger.info(
            "Face emotion reported: user_id=%s emotion=%s confidence=%.3f captured_at=%s",
            current_user_id,
            payload.emotion,
            payload.confidence,
            payload.captured_at.isoformat(),
        )

        bundle = EmotionOrchestrationService().evaluate_sustained_face(
            payload.emotion, payload.confidence, payload.sustained_negative_ms
        )
        bundle_event = bundle.to_event() if bundle else None
        if bundle_event:
            # 摄像头主动提示先询问用户，避免未经点击就自动播放声音。
            bundle_event["auto_actions"] = []
        return FaceEmotionReportResponse(
            success=True, message="表情情绪记录成功", service_bundle=bundle_event
        )

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error("Face emotion report failed: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"表情情绪上报失败: {str(e)}",
        )
