"""Business logic for drawing generation."""

from typing import Optional

import logging

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.database import Diary, DiaryMedia, DiarySummary

from .schemas import DEFAULT_DRAWING_SIZE, EmotionPrompts
from .volcengine_client import VolcengineAPIError, VolcengineClient, get_volcengine_client

logger = logging.getLogger(__name__)


class DrawingServiceError(Exception):
    """Raised when drawing generation fails."""


class DrawingService:
    """Service for text-to-image, sketch enhancement, and diary-to-image."""

    STYLE_PROMPTS = {
        "watercolor": "watercolor style, soft tones, painterly details",
        "oil": "oil painting style, rich texture, layered brush strokes",
        "cartoon": "cartoon style, clean lines, vivid colors",
        "realistic": "realistic style, detailed lighting, high fidelity",
        "anime": "anime style, refined illustration, expressive composition",
        "sketch": "pencil sketch style, visible line work",
    }

    ENHANCE_BASE_PROMPT = (
        "Transform this sketch into a polished artwork while preserving the original composition "
        "and subject. Add color, lighting, depth, and coherent details."
    )

    # 情绪检测映射：数据库情绪 -> positive/negative
    EMOTION_TYPE_MAP = {
        "开心": "positive",
        "兴奋": "positive",
        "平静": "positive",
        "低落": "negative",
        "焦虑": "negative",
        "愤怒": "negative",
        "复杂": "negative",
        "中性": "positive",
    }

    # 正向情绪提示词（给用户看的描述是中性的）
    POSITIVE_PROMPTS = [
        {
            "emotion_type": "positive",
            "display_name": "温暖治愈",
            "prompt_preview": "阳光、温暖、温柔的色调",
            "full_prompt": "Create a warm and comforting illustration with soft, gentle colors. Include warm sunlight, cozy atmosphere, and a sense of peace. The scene should convey comfort and emotional healing. {style}",
        },
        {
            "emotion_type": "positive",
            "display_name": "自然宁静",
            "prompt_preview": "自然风景、平静宁和",
            "full_prompt": "Create a serene natural landscape illustration with peaceful atmosphere. Include elements like gentle hills, flowing water, or soft clouds. Convey a sense of tranquility and inner peace. {style}",
        },
        {
            "emotion_type": "positive",
            "display_name": "美好时光",
            "prompt_preview": "温馨场景、美好回忆",
            "full_prompt": "Create a heartwarming illustration depicting a beautiful moment. Use soft lighting and warm colors to capture the essence of joy and gratitude. Convey a sense of cherish and warmth. {style}",
        },
    ]

    # 负向情绪提示词（给用户看的描述是中性的）
    NEGATIVE_PROMPTS = [
        {
            "emotion_type": "negative",
            "display_name": "雨过天晴",
            "prompt_preview": "阴雨过后、阳光重现",
            "full_prompt": "Create an illustration showing a scene after the rain. Dark clouds giving way to light, with rays of sunshine breaking through. The atmosphere shifts from gloom to hope, with delicate water droplets and emerging brightness. {style}",
        },
        {
            "emotion_type": "negative",
            "display_name": "破茧成蝶",
            "prompt_preview": "蜕变、成长、新生",
            "full_prompt": "Create an illustration representing transformation and renewal. Show elements of growth and new beginnings - perhaps a flower blooming, a butterfly emerging, or seeds sprouting. Convey hope and the promise of better days. {style}",
        },
        {
            "emotion_type": "negative",
            "display_name": "温暖陪伴",
            "prompt_preview": "陪伴、关怀、慰藉",
            "full_prompt": "Create a comforting illustration with warm, soft colors. Include elements that suggest care and companionship - perhaps gentle lighting, cozy atmosphere, or subtle symbols of support. The mood should shift from heaviness to comfort. {style}",
        },
    ]

    def __init__(self, client: Optional[VolcengineClient] = None):
        self.client = client or get_volcengine_client()

    def _get_emotion_type(self, primary_emotion: str) -> str:
        """根据主要情绪判断是正向还是负向"""
        return self.EMOTION_TYPE_MAP.get(primary_emotion, "positive")

    async def get_emotion_prompts(
        self,
        db: AsyncSession,
        diary_id: int,
    ) -> dict:
        """
        获取日记的情绪和推荐提示词

        Args:
            db: 数据库会话
            diary_id: 日记ID

        Returns:
            dict: {
                "detected_emotion": str,    # 检测到的情绪
                "emotion_score": int,        # 情绪分数
                "recommendations": [         # 推荐的提示词列表
                    {
                        "emotion_type": str,
                        "display_name": str,
                        "prompt_preview": str,
                        "full_prompt": str,
                    },
                    ...
                ]
            }
        """
        # 查询日记摘要中的情绪信息
        stmt = select(DiarySummary).where(DiarySummary.diary_id == diary_id)
        result = await db.execute(stmt)
        summary = result.scalar_one_or_none()

        if summary and summary.primary_emotion:
            detected_emotion = summary.primary_emotion
            emotion_score = summary.emotion_score or 50
        else:
            # 如果没有摘要，使用日记内容进行简单判断
            diary_stmt = select(Diary).where(Diary.id == diary_id)
            diary_result = await db.execute(diary_stmt)
            diary = diary_result.scalar_one_or_none()

            if diary and diary.content:
                detected_emotion = self._simple_emotion_detection(diary.content)
                emotion_score = 50
            else:
                detected_emotion = "中性"
                emotion_score = 50

        emotion_type = self._get_emotion_type(detected_emotion)

        # 根据情绪类型选择推荐提示词
        if emotion_type == "positive":
            recommendations = self.POSITIVE_PROMPTS
        else:
            recommendations = self.NEGATIVE_PROMPTS

        return {
            "detected_emotion": detected_emotion,
            "emotion_score": emotion_score,
            "recommendations": recommendations,
        }

    def _simple_emotion_detection(self, content: str) -> str:
        """
        简单的情绪检测（当没有摘要时使用）

        基于关键词判断情绪倾向
        """
        content_lower = content.lower()

        # 负面情绪关键词
        negative_keywords = ["难过", "伤心", "痛苦", "焦虑", "担心", "害怕", "失望", "沮丧", "郁闷", "孤独", "累", "疲惫", "压力", "烦恼"]
        # 正面情绪关键词
        positive_keywords = ["开心", "高兴", "快乐", "幸福", "满足", "感恩", "温暖", "甜蜜", "美好", "兴奋", "激动", "快乐", "愉快"]

        negative_count = sum(1 for kw in negative_keywords if kw in content_lower)
        positive_count = sum(1 for kw in positive_keywords if kw in content_lower)

        if negative_count > positive_count:
            return "低落"
        elif positive_count > negative_count:
            return "开心"
        else:
            return "中性"

    async def generate_from_text(
        self,
        prompt: str,
        style: str = "watercolor",
        size: str = DEFAULT_DRAWING_SIZE,
        watermark: bool = False,
    ) -> dict:
        style_suffix = self.STYLE_PROMPTS.get(style, self.STYLE_PROMPTS["watercolor"])
        full_prompt = f"{prompt}, {style_suffix}"

        try:
            image_url = await self.client.text_to_image(
                prompt=full_prompt,
                size=size,
                watermark=watermark,
            )
            return {"image_url": image_url, "prompt_used": full_prompt}
        except VolcengineAPIError as exc:
            logger.error("generate_from_text failed: %s", exc)
            raise DrawingServiceError(f"Failed to generate drawing: {exc}") from exc

    async def enhance_sketch(
        self,
        image_url: str,
        enhancement_prompt: Optional[str] = None,
        style: str = "watercolor",
        size: str = DEFAULT_DRAWING_SIZE,
        watermark: bool = False,
    ) -> dict:
        style_suffix = self.STYLE_PROMPTS.get(style, self.STYLE_PROMPTS["watercolor"])
        prompt_parts = [self.ENHANCE_BASE_PROMPT]
        if enhancement_prompt and enhancement_prompt.strip():
            prompt_parts.append(enhancement_prompt.strip())
        prompt_parts.append(style_suffix)
        full_prompt = ". ".join(prompt_parts)

        try:
            result_url = await self.client.image_to_image(
                image_url=image_url,
                prompt=full_prompt,
                size=size,
                watermark=watermark,
            )
            return {"image_url": result_url, "prompt_used": full_prompt}
        except VolcengineAPIError as exc:
            logger.error("enhance_sketch failed: %s", exc)
            raise DrawingServiceError(f"Failed to enhance sketch: {exc}") from exc

    async def generate_from_diary(
        self,
        db: AsyncSession,
        diary_id: int,
        style: str = "watercolor",
        include_emotion_transform: bool = True,
        custom_prompt: Optional[str] = None,
    ) -> dict:
        stmt = select(Diary).where(Diary.id == diary_id)
        result = await db.execute(stmt)
        diary = result.scalar_one_or_none()

        if not diary:
            raise DrawingServiceError(f"Diary {diary_id} not found")

        diary_content = self._extract_diary_text(diary)
        if not diary_content:
            raise DrawingServiceError("Diary content is empty")

        # 如果用户提供了自定义提示词，直接使用
        if custom_prompt and custom_prompt.strip():
            style_suffix = self.STYLE_PROMPTS.get(style, self.STYLE_PROMPTS["watercolor"])
            prompt = f"{custom_prompt.strip()} {style_suffix}"
        else:
            prompt = self._build_diary_prompt(
                content=diary_content,
                style=style,
                include_emotion_transform=include_emotion_transform,
            )

        try:
            image_url = await self.client.text_to_image(
                prompt=prompt,
                size=DEFAULT_DRAWING_SIZE,
                watermark=False,
            )

            # Persist generated artwork as a diary media item so it appears immediately in diary detail.
            max_sort_stmt = select(func.max(DiaryMedia.sort_order)).where(DiaryMedia.diary_id == diary_id)
            max_sort = (await db.execute(max_sort_stmt)).scalar_one_or_none()
            next_sort_order = (max_sort if max_sort is not None else -1) + 1

            db.add(
                DiaryMedia(
                    diary_id=diary_id,
                    media_type="image",
                    media_url=image_url,
                    sort_order=next_sort_order,
                )
            )
            await db.commit()

            return {
                "image_url": image_url,
                "prompt_used": prompt,
                "diary_summary": diary_content[:200] + ("..." if len(diary_content) > 200 else ""),
            }
        except VolcengineAPIError as exc:
            logger.error("generate_from_diary failed: %s", exc)
            raise DrawingServiceError(f"Failed to generate drawing from diary: {exc}") from exc
        except Exception as exc:
            await db.rollback()
            logger.error("generate_from_diary persistence failed: %s", exc)
            raise DrawingServiceError(f"Failed to save generated drawing: {exc}") from exc

    def _extract_diary_text(self, diary: Diary) -> str:
        parts: list[str] = []
        if diary.title:
            parts.append(diary.title)
        if diary.content:
            parts.append(diary.content)
        if hasattr(diary, "media_items") and diary.media_items:
            for item in diary.media_items:
                if item.media_type == "text" and item.content:
                    parts.append(item.content)
        return " ".join(parts).strip()

    def _build_diary_prompt(
        self,
        content: str,
        style: str,
        include_emotion_transform: bool,
    ) -> str:
        excerpt = content[:500]
        style_suffix = self.STYLE_PROMPTS.get(style, self.STYLE_PROMPTS["watercolor"])

        if include_emotion_transform:
            return (
                f"Create a healing illustration inspired by this diary entry: {excerpt}. "
                f"Even if the original text contains difficult emotions, the image should carry hope, "
                f"warmth, and gentle recovery. {style_suffix}"
            )

        return f"Create an illustration inspired by this diary entry: {excerpt}. {style_suffix}"
