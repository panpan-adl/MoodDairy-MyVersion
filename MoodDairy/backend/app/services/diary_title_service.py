"""Recommend an editable title without creating or changing a diary."""
import asyncio
from fastapi import HTTPException
from pydantic import BaseModel, Field, field_validator
from app.services.personal_agent_service import generate_json


class TitleRecommendationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=12000)

    @field_validator('text')
    @classmethod
    def require_text(cls, value):
        if not value.strip():
            raise ValueError('Diary text is required')
        return value.strip()


class TitleRecommendationResponse(BaseModel):
    title: str = Field(min_length=1, max_length=30)

    @field_validator('title', mode='before')
    @classmethod
    def clean_title(cls, value):
        if isinstance(value, str):
            value = value.strip().strip('"“”「」《》').strip()
            if '\n' in value or '\r' in value:
                raise ValueError('A title must be a single line')
        return value


async def recommend_title(text: str) -> TitleRecommendationResponse:
    try:
        async with asyncio.timeout(25):
            return await generate_json(
                '根据用户这一篇日记的文字，推荐一个自然、具体的中文标题，优先6至18个字，最多30个字符。'
                '只概括原文明确写出的经历或感受，不编造人物、事件、天气或情绪，不作心理评估。'
                '不要使用“无标题”“日记标题”等占位词，不加引号、标题前缀或说明。返回title字段。',
                {'diary_text': text}, TitleRecommendationResponse,
                max_tokens=128, thinking=False)
    except Exception:
        raise HTTPException(503, '暂时无法推荐标题，你可以手动填写或直接保存。') from None
