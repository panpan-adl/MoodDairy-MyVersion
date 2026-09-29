"""Multimodal insight aggregation for diary growth portraits."""

from __future__ import annotations

from collections import Counter
from datetime import date, timedelta
from typing import Any, Dict, List, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload, selectinload

from app.models.database import Diary, DiaryMedia, DiaryMultimodalInsight, DiarySummary, VoiceTranscription
from app.models.schemas import (
    DiaryMultimodalInsightResponse,
    FusionInsight,
    GrowthPortraitResponse,
    GrowthTrendPoint,
    ModalityInsight,
    PositiveEventPoint,
)
from app.utils.error_handler import NotFoundError


POSITIVE_EMOTIONS = {"开心", "快乐", "兴奋", "平静", "感恩", "满足", "幸福"}
NEGATIVE_EMOTIONS = {"焦虑", "难过", "生气", "愤怒", "压力", "疲惫", "沮丧", "低落"}
POSITIVE_HINTS = ["开心", "感谢", "完成", "收获", "进步", "陪伴", "放松", "温暖", "惊喜"]
STRESS_HINTS = ["焦虑", "压力", "崩溃", "疲惫", "赶工", "失眠", "争吵", "担心", "难受"]
DRAWING_HINTS = ["绘画", "画画", "涂鸦", "sketch", "drawing"]


class MultimodalInsightService:
    """Build and query per-diary multimodal insights plus longitudinal portraits."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.analysis_version = 1

    async def analyze_diary(self, diary_id: int, user_id: Optional[int] = None) -> DiaryMultimodalInsightResponse:
        diary = await self._load_diary(diary_id, user_id=user_id)
        response = await self._analyze_diary_entity(diary)
        await self._save_or_update(response)
        return response

    async def get_diary_insight(
        self,
        diary_id: int,
        user_id: Optional[int] = None,
        auto_create: bool = True,
    ) -> DiaryMultimodalInsightResponse:
        diary = await self._load_diary(diary_id, user_id=user_id)
        insight = diary.multimodal_insight
        if insight is None and auto_create:
            return await self.analyze_diary(diary_id, user_id=user_id)
        if insight is None:
            raise NotFoundError(f"Multimodal insight for diary {diary_id} not found")
        return DiaryMultimodalInsightResponse(
            diary_id=diary.id,
            diary_date=diary.diary_date,
            text_analysis=ModalityInsight.model_validate(insight.text_analysis or {"available": False}),
            voice_analysis=ModalityInsight.model_validate(insight.voice_analysis or {"available": False}),
            image_analysis=ModalityInsight.model_validate(insight.image_analysis or {"available": False}),
            video_analysis=ModalityInsight.model_validate(insight.video_analysis or {"available": False}),
            drawing_analysis=ModalityInsight.model_validate(insight.drawing_analysis or {"available": False}),
            fusion_analysis=FusionInsight.model_validate(insight.fusion_analysis),
            analysis_version=insight.analysis_version,
            created_at=insight.created_at,
            updated_at=insight.updated_at,
        )

    async def get_growth_portrait(
        self,
        user_id: int,
        days: int = 30,
        end_date: Optional[date] = None,
    ) -> GrowthPortraitResponse:
        portrait_end = end_date or date.today()
        portrait_start = portrait_end - timedelta(days=max(days - 1, 0))

        stmt = (
            select(Diary)
            .options(
                joinedload(Diary.summary),
                joinedload(Diary.multimodal_insight),
            )
            .where(Diary.user_id == user_id)
            .where(Diary.diary_date >= portrait_start)
            .where(Diary.diary_date <= portrait_end)
            .order_by(Diary.diary_date.asc())
        )
        diaries = list((await self.db.execute(stmt)).scalars().unique().all())

        if not diaries:
            return GrowthPortraitResponse(
                user_id=user_id,
                range_start=portrait_start,
                range_end=portrait_end,
                summary="最近还没有足够的多模态记录，继续记录文字、语音或图片后即可生成成长画像。",
                self_awareness_feedback="建议先持续记录一周以上，让系统逐步识别你的情绪节奏、压力来源和积极事件模式。",
            )

        responses: List[DiaryMultimodalInsightResponse] = []
        for diary in diaries:
            if diary.multimodal_insight is None:
                responses.append(await self._analyze_diary_entity(diary, persist=True))
            else:
                responses.append(
                    DiaryMultimodalInsightResponse(
                        diary_id=diary.id,
                        diary_date=diary.diary_date,
                        text_analysis=ModalityInsight.model_validate(diary.multimodal_insight.text_analysis or {"available": False}),
                        voice_analysis=ModalityInsight.model_validate(diary.multimodal_insight.voice_analysis or {"available": False}),
                        image_analysis=ModalityInsight.model_validate(diary.multimodal_insight.image_analysis or {"available": False}),
                        video_analysis=ModalityInsight.model_validate(diary.multimodal_insight.video_analysis or {"available": False}),
                        drawing_analysis=ModalityInsight.model_validate(diary.multimodal_insight.drawing_analysis or {"available": False}),
                        fusion_analysis=FusionInsight.model_validate(diary.multimodal_insight.fusion_analysis),
                        analysis_version=diary.multimodal_insight.analysis_version,
                        created_at=diary.multimodal_insight.created_at,
                        updated_at=diary.multimodal_insight.updated_at,
                    )
                )

        emotion_curve = [
            GrowthTrendPoint(
                date=item.diary_date,
                value=item.fusion_analysis.overall_emotion_score,
                label=item.fusion_analysis.overall_emotion,
            )
            for item in responses
        ]
        stress_curve = [
            GrowthTrendPoint(date=item.diary_date, value=item.fusion_analysis.stress_score)
            for item in responses
        ]

        positive_events: List[PositiveEventPoint] = []
        keyword_counter: Counter[str] = Counter()
        modality_counter: Counter[str] = Counter()
        for item in responses:
            keyword_counter.update(item.fusion_analysis.growth_keywords)
            for modality_name, analysis in (
                ("text", item.text_analysis),
                ("voice", item.voice_analysis),
                ("image", item.image_analysis),
                ("video", item.video_analysis),
                ("drawing", item.drawing_analysis),
            ):
                if analysis.available:
                    modality_counter[modality_name] += 1
            if item.fusion_analysis.positive_event_score >= 60:
                positive_events.append(
                    PositiveEventPoint(
                        diary_id=item.diary_id,
                        date=item.diary_date,
                        title=item.fusion_analysis.positive_events[0] if item.fusion_analysis.positive_events else "积极时刻",
                        summary=item.fusion_analysis.explanation,
                        score=item.fusion_analysis.positive_event_score,
                    )
                )

        positive_events = sorted(positive_events, key=lambda event: (event.score, event.date), reverse=True)[:10]
        avg_emotion = round(sum(point.value for point in emotion_curve) / len(emotion_curve))
        avg_stress = round(sum(point.value for point in stress_curve) / len(stress_curve))
        dominant_keywords = [keyword for keyword, _ in keyword_counter.most_common(6)]
        strongest_positive = positive_events[0].title if positive_events else "尚未识别明显高峰"

        return GrowthPortraitResponse(
            user_id=user_id,
            range_start=portrait_start,
            range_end=portrait_end,
            emotion_curve=emotion_curve,
            stress_curve=stress_curve,
            positive_events=positive_events,
            growth_keywords=dominant_keywords,
            multimodal_distribution=dict(modality_counter),
            summary=(
                f"最近{len(responses)}篇记录的平均情绪分为{avg_emotion}，平均压力分为{avg_stress}。"
                f"最突出的积极事件是“{strongest_positive}”。"
            ),
            self_awareness_feedback=self._build_portrait_feedback(avg_emotion, avg_stress, dominant_keywords),
        )

    async def _load_diary(self, diary_id: int, user_id: Optional[int] = None) -> Diary:
        stmt = (
            select(Diary)
            .options(
                joinedload(Diary.summary),
                joinedload(Diary.multimodal_insight),
                selectinload(Diary.media_items),
                selectinload(Diary.voice_transcriptions),
                selectinload(Diary.media_files),
            )
            .where(Diary.id == diary_id)
        )
        if user_id is not None:
            stmt = stmt.where(Diary.user_id == user_id)
        diary = (await self.db.execute(stmt)).scalars().unique().one_or_none()
        if diary is None:
            raise NotFoundError(f"Diary {diary_id} not found")
        return diary

    async def _analyze_diary_entity(self, diary: Diary, persist: bool = False) -> DiaryMultimodalInsightResponse:
        text_analysis = self._analyze_text(diary)
        voice_analysis = self._analyze_voice(diary.voice_transcriptions)
        image_analysis = self._analyze_visual(diary.media_items, media_type="image")
        video_analysis = self._analyze_visual(diary.media_items, media_type="video")
        drawing_analysis = self._analyze_drawing(diary)
        fusion_analysis = self._fuse(
            diary=diary,
            text_analysis=text_analysis,
            voice_analysis=voice_analysis,
            image_analysis=image_analysis,
            video_analysis=video_analysis,
            drawing_analysis=drawing_analysis,
        )
        response = DiaryMultimodalInsightResponse(
            diary_id=diary.id,
            diary_date=diary.diary_date,
            text_analysis=text_analysis,
            voice_analysis=voice_analysis,
            image_analysis=image_analysis,
            video_analysis=video_analysis,
            drawing_analysis=drawing_analysis,
            fusion_analysis=fusion_analysis,
            analysis_version=self.analysis_version,
            created_at=getattr(diary.multimodal_insight, "created_at", diary.created_at),
            updated_at=diary.updated_at,
        )
        if persist:
            await self._save_or_update(response)
        return response

    def _analyze_text(self, diary: Diary) -> ModalityInsight:
        summary = diary.summary
        text_content = " ".join(filter(None, [diary.title, diary.content, summary.summary if summary else None]))
        if not text_content.strip():
            return ModalityInsight(available=False)

        emotion = summary.primary_emotion if summary and summary.primary_emotion else (diary.mood_type or "中性")
        emotion_score = self._normalize_score(
            summary.emotion_score if summary and summary.emotion_score is not None else diary.mood_score or 50
        )
        stress_score = self._estimate_stress(text_content, emotion)
        positive_score = self._estimate_positive(text_content, emotion)
        evidence = list(filter(None, [
            summary.highlight_summary if summary and summary.has_highlight else None,
            summary.small_happiness_content if summary and summary.has_small_happiness else None,
        ]))
        tags = list(dict.fromkeys((summary.keywords if summary and summary.keywords else [])[:6]))
        return ModalityInsight(
            available=True,
            emotion=emotion,
            emotion_score=emotion_score,
            stress_score=stress_score,
            positive_event_score=positive_score,
            confidence_score=80 if summary else 65,
            summary=(summary.summary if summary and summary.summary else (diary.content or "")[:120]),
            evidence=evidence,
            tags=tags,
        )

    def _analyze_voice(self, voice_transcriptions: List[VoiceTranscription]) -> ModalityInsight:
        if not voice_transcriptions:
            return ModalityInsight(available=False)

        emotions = [item.detected_emotion for item in voice_transcriptions if item.detected_emotion]
        top_emotion = Counter(emotions).most_common(1)[0][0] if emotions else "中性"
        avg_score = round(
            sum(float(item.emotion_score or 50) for item in voice_transcriptions) / len(voice_transcriptions)
        )
        merged_text = " ".join(
            filter(None, [item.processed_text or item.original_text for item in voice_transcriptions])
        )
        return ModalityInsight(
            available=True,
            emotion=top_emotion,
            emotion_score=self._normalize_score(avg_score),
            stress_score=self._estimate_stress(merged_text, top_emotion),
            positive_event_score=self._estimate_positive(merged_text, top_emotion),
            confidence_score=75,
            summary=(merged_text[:120] if merged_text else "检测到语音记录"),
            evidence=[item.processed_text[:40] for item in voice_transcriptions if item.processed_text][:3],
            tags=["voice", f"{len(voice_transcriptions)}条语音"],
        )

    def _analyze_visual(self, media_items: List[DiaryMedia], media_type: str) -> ModalityInsight:
        items = [item for item in media_items if item.media_type == media_type]
        if not items:
            return ModalityInsight(available=False)

        joined_text = " ".join(filter(None, [item.content for item in items]))
        positive_score = min(100, 45 + len(items) * 8 + self._hint_count(joined_text, POSITIVE_HINTS) * 5)
        stress_score = max(5, 35 - len(items) * 3 + self._hint_count(joined_text, STRESS_HINTS) * 6)
        emotion = "积极" if positive_score >= stress_score else "紧张"
        tags = [media_type, f"{len(items)}项"]
        return ModalityInsight(
            available=True,
            emotion=emotion,
            emotion_score=self._normalize_score(positive_score if emotion == "积极" else 100 - stress_score),
            stress_score=self._normalize_score(stress_score),
            positive_event_score=self._normalize_score(positive_score),
            confidence_score=55 if joined_text else 45,
            summary=joined_text[:120] if joined_text else f"检测到{len(items)}个{media_type}媒体记录",
            evidence=[item.content[:40] for item in items if item.content][:3],
            tags=tags,
        )

    def _analyze_drawing(self, diary: Diary) -> ModalityInsight:
        artwork_analysis = diary.summary.artwork_analysis if diary.summary and diary.summary.artwork_analysis else {}
        drawing_items = [
            item for item in diary.media_items
            if item.media_type == "image" and any(hint in (item.content or "").lower() for hint in DRAWING_HINTS)
        ]
        if not artwork_analysis and not drawing_items:
            return ModalityInsight(available=False)

        prompt_preview = artwork_analysis.get("prompt_preview") if isinstance(artwork_analysis, dict) else None
        evidence = [prompt_preview] if prompt_preview else []
        evidence.extend(item.content[:40] for item in drawing_items if item.content)
        positive_score = 70 if prompt_preview else 58
        return ModalityInsight(
            available=True,
            emotion=artwork_analysis.get("emotion_type", "积极") if isinstance(artwork_analysis, dict) else "积极",
            emotion_score=artwork_analysis.get("emotion_score", 68) if isinstance(artwork_analysis, dict) else 68,
            stress_score=28,
            positive_event_score=positive_score,
            confidence_score=60,
            summary=prompt_preview or "检测到绘画/涂鸦相关记录",
            evidence=evidence[:3],
            tags=["drawing", f"{len(drawing_items)}项"],
        )

    def _fuse(
        self,
        diary: Diary,
        text_analysis: ModalityInsight,
        voice_analysis: ModalityInsight,
        image_analysis: ModalityInsight,
        video_analysis: ModalityInsight,
        drawing_analysis: ModalityInsight,
    ) -> FusionInsight:
        available = [
            analysis
            for analysis in [text_analysis, voice_analysis, image_analysis, video_analysis, drawing_analysis]
            if analysis.available
        ]
        emotion_score = round(sum((analysis.emotion_score or 50) for analysis in available) / len(available)) if available else 50
        stress_score = round(sum((analysis.stress_score or 40) for analysis in available) / len(available)) if available else 40
        positive_score = round(sum((analysis.positive_event_score or 45) for analysis in available) / len(available)) if available else 45
        confidence_score = round(sum((analysis.confidence_score or 50) for analysis in available) / len(available)) if available else 50

        emotion_candidates = [analysis.emotion for analysis in available if analysis.emotion]
        overall_emotion = Counter(emotion_candidates).most_common(1)[0][0] if emotion_candidates else "中性"
        if stress_score >= 65:
            emotional_stability = "波动明显"
        elif stress_score >= 45:
            emotional_stability = "轻度波动"
        else:
            emotional_stability = "相对稳定"

        summary = diary.summary
        positive_events = list(
            dict.fromkeys(
                [
                    item
                    for item in [
                        summary.highlight_summary if summary and summary.has_highlight else None,
                        summary.small_happiness_content if summary and summary.has_small_happiness else None,
                        text_analysis.summary if positive_score >= 60 else None,
                    ]
                    if item
                ]
            )
        )[:3]
        growth_keywords = list(
            dict.fromkeys(
                (text_analysis.tags or [])
                + (drawing_analysis.tags or [])
                + [overall_emotion, emotional_stability]
            )
        )[:6]
        stress_triggers = list(
            dict.fromkeys(
                [
                    evidence
                    for analysis in available
                    for evidence in analysis.evidence
                    if self._hint_count(evidence, STRESS_HINTS) > 0
                ]
            )
        )[:3]
        explanation = (
            f"本次记录以{overall_emotion}为主，综合情绪分{emotion_score}，压力分{stress_score}，"
            f"积极事件分{positive_score}。系统结合文字、语音和视觉线索，判断你的状态{emotional_stability}。"
        )
        return FusionInsight(
            overall_emotion=overall_emotion,
            overall_emotion_score=self._normalize_score(emotion_score),
            stress_score=self._normalize_score(stress_score),
            positive_event_score=self._normalize_score(positive_score),
            confidence_score=self._normalize_score(confidence_score),
            emotional_stability=emotional_stability,
            positive_events=positive_events,
            stress_triggers=stress_triggers,
            growth_keywords=growth_keywords,
            explanation=explanation,
        )

    async def _save_or_update(self, response: DiaryMultimodalInsightResponse) -> None:
        stmt = select(DiaryMultimodalInsight).where(DiaryMultimodalInsight.diary_id == response.diary_id)
        insight = (await self.db.execute(stmt)).scalar_one_or_none()
        payload = {
            "text_analysis": response.text_analysis.model_dump(),
            "voice_analysis": response.voice_analysis.model_dump(),
            "image_analysis": response.image_analysis.model_dump(),
            "video_analysis": response.video_analysis.model_dump(),
            "drawing_analysis": response.drawing_analysis.model_dump(),
            "fusion_analysis": response.fusion_analysis.model_dump(),
            "stress_score": response.fusion_analysis.stress_score,
            "positive_event_score": response.fusion_analysis.positive_event_score,
            "confidence_score": response.fusion_analysis.confidence_score,
            "analysis_version": response.analysis_version,
        }
        if insight is None:
            insight = DiaryMultimodalInsight(diary_id=response.diary_id, **payload)
            self.db.add(insight)
        else:
            for key, value in payload.items():
                setattr(insight, key, value)
        await self.db.flush()

    def _estimate_stress(self, text: str, emotion: Optional[str]) -> int:
        base = 30
        if emotion in NEGATIVE_EMOTIONS:
            base += 20
        base += self._hint_count(text, STRESS_HINTS) * 8
        base -= self._hint_count(text, POSITIVE_HINTS) * 4
        return self._normalize_score(base)

    def _estimate_positive(self, text: str, emotion: Optional[str]) -> int:
        base = 45
        if emotion in POSITIVE_EMOTIONS:
            base += 18
        base += self._hint_count(text, POSITIVE_HINTS) * 7
        base -= self._hint_count(text, STRESS_HINTS) * 5
        return self._normalize_score(base)

    def _hint_count(self, text: str, hints: List[str]) -> int:
        normalized = (text or "").lower()
        return sum(1 for hint in hints if hint.lower() in normalized)

    def _normalize_score(self, score: Any) -> int:
        return max(0, min(100, int(round(float(score)))))

    def _build_portrait_feedback(self, avg_emotion: int, avg_stress: int, keywords: List[str]) -> str:
        keyword_text = "、".join(keywords[:4]) if keywords else "尚未形成稳定关键词"
        if avg_stress >= 65:
            return f"你最近的压力波动偏高，建议优先关注触发场景并安排恢复节奏。当前成长关键词集中在：{keyword_text}。"
        if avg_emotion >= 65:
            return f"你的整体状态偏积极，说明你在稳定积累正向体验。当前成长关键词集中在：{keyword_text}。"
        return f"你的状态整体中性且可塑性较高，适合持续记录并观察变化。当前成长关键词集中在：{keyword_text}。"
