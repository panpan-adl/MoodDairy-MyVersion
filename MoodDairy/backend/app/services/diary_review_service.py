"""Explicit, owner-scoped diary reflection. Media bytes never enter this flow."""
import asyncio
import hashlib
import json
import weakref
from datetime import datetime, timezone

from fastapi import HTTPException
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator
from sqlalchemy import select

from app.models.database import Diary, DiaryMultimodalInsight
from app.security.field_crypto import decrypt_field, encrypt_field
from app.services.multimodal_insight_service import MultimodalInsightService
from app.services.personal_agent_service import generate_json

CACHE_KEY = 'gentle_review'
FAILURE = '这次没能整理出来，你写下的内容还在，可以稍后再试。'
MEDIA_ONLY = '这些画面也值得留下。愿意的话，可以补一句当时的感受。'
_locks = weakref.WeakValueDictionary()


class ReviewText(BaseModel):
    # The product range is approximate; allow a small margin rather than
    # discarding an otherwise useful response that is a couple of characters short.
    body: str = Field(min_length=70, max_length=160)
    question: str | None = Field(default=None, max_length=60)

    @field_validator('body', 'question', mode='before')
    @classmethod
    def strip_text(cls, value):
        return value.strip() if isinstance(value, str) else value

    @field_validator('question')
    @classmethod
    def one_question(cls, value):
        if value and value.count('？') + value.count('?') > 1:
            raise ValueError('Only one optional question is allowed')
        return value or None

    @model_validator(mode='after')
    def at_most_one_question(self):
        count = self.body.count('？') + self.body.count('?')
        if count > 1:
            raise ValueError('Only one optional question is allowed')
        if count:
            self.question = None
        return self


class DiaryReviewResponse(BaseModel):
    diary_id: int
    content_version: str
    body: str | None = None
    question: str | None = None
    generated_at: str | None = None
    stale: bool = False
    has_text: bool
    attachment_note: str | None = None
    clues: list[str] = Field(default_factory=list)


def source_text(diary):
    """Canonical saved text only, with stable ordering and no media metadata."""
    clean = lambda value: (decrypt_field(value) or '').strip()
    return {
        'title': clean(diary.title),
        'body': clean(diary.content),
        'text_blocks': [clean(m.content) for m in sorted(diary.media_items, key=lambda m: (m.sort_order or 0, m.id))
                        if m.media_type == 'text' and clean(m.content)],
        'transcripts': [clean(v.processed_text or v.original_text)
                        for v in sorted(diary.voice_transcriptions, key=lambda v: v.id)
                        if clean(v.processed_text or v.original_text)],
    }


def content_version(source):
    return hashlib.sha256(json.dumps(source, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def has_text(source):
    return bool(source['body'] or source['text_blocks'] or source['transcripts'] or
                source['title'] not in ('', '无标题', '无标题日记', '新日记'))


def review_view(diary):
    source = source_text(diary)
    version = content_version(source)
    cache = ((diary.multimodal_insight.fusion_analysis or {}).get(CACHE_KEY) or {}) if diary.multimodal_insight else {}
    counts = [(label, sum(m.media_type == kind for m in diary.media_items))
              for kind, label in [('image', '张照片'), ('video', '段视频'), ('audio', '段录音')]]
    note = '这篇日记还留下了 ' + '、'.join(f'{n} {label}' for label, n in counts if n) if any(n for _, n in counts) else None
    clues = []
    # These are actual saved text/transcripts, not inferred visual or fallback scores.
    if source['body']:
        clues.append('日记正文：' + source['body'][:240])
    for text in source['text_blocks']:
        clues.append('文字记录：' + text[:240])
    for text in source['transcripts']:
        clues.append('语音转写：' + text[:240])
    summary = diary.summary
    text_evidence = source['body'] + '\n' + '\n'.join(source['text_blocks'])
    analysis = (summary.emotion_analysis or {}) if summary else {}
    grounded = any(isinstance(line, str) and line.strip() and line.strip() in text_evidence
                   for line in analysis.get('key_sentences', []))
    if summary and grounded:
        if summary.primary_emotion:
            clues.append('已有文字分析参考：' + summary.primary_emotion +
                         (f'，情绪参考分 {summary.emotion_score}/100' if summary.emotion_score is not None and analysis.get('emotion_score') == summary.emotion_score else '') +
                         '（来源：已保存的文字分析，并非心理评估）')
    for voice in diary.voice_transcriptions:
        if voice.detected_emotion and (voice.processed_text or voice.original_text) and float(voice.confidence or 0) > 0:
            clues.append('已有语音分析参考：' + voice.detected_emotion +
                         (f'，情绪参考分 {float(voice.emotion_score):g}/100' if voice.emotion_score is not None else '') +
                         '（来源：已保存的语音分析，并非心理评估）')
    return DiaryReviewResponse(diary_id=diary.id, content_version=version,
                              body=decrypt_field(cache.get('body')), question=decrypt_field(cache.get('question')),
                              generated_at=cache.get('generated_at'),
                              stale=bool(cache and cache.get('content_version') != version),
                              has_text=has_text(source), attachment_note=note, clues=clues)


class DiaryReviewService:
    def __init__(self, db):
        self.db = db
        self.insights = MultimodalInsightService(db)

    async def read(self, diary_id, user_id):
        return review_view(await self.insights._load_diary(diary_id, user_id))

    async def generate(self, diary_id, user_id, expected_version=None, refresh=False):
        lock = _locks.setdefault((user_id, diary_id), asyncio.Lock())
        if lock.locked():
            raise HTTPException(409, '正在帮你回顾，请稍等一下。')
        async with lock:
            diary = await self.insights._load_diary(diary_id, user_id)
            view = review_view(diary)
            if expected_version and view.content_version != expected_version:
                raise HTTPException(409, '日记内容已更新，请重新打开回顾。')
            if not view.has_text:
                return view
            if view.body and not view.stale and not refresh:
                return view
            # Cross-worker exclusion without locking diary edits for the model's duration.
            if self.db.bind.dialect.name == 'postgresql':
                from sqlalchemy import text
                acquired = await self.db.scalar(text('SELECT pg_try_advisory_xact_lock(:key)'), {'key': diary_id})
                if not acquired:
                    raise HTTPException(409, '正在帮你回顾，请稍等一下。')
            source = source_text(diary)
            instruction = (
                    '你是温和、自然的日记陪伴者。只围绕给出的这一篇日记，body必须80—140个汉字及标点。'
                    '用第二人称“你”对写日记的人回应，这是陪伴对话，不是代写或扩写日记，不用“我”替用户叙事。'
                    'body只写陈述句，唯一可选的问题放在question中。'
                    '正文目标100—120个汉字及标点，简洁自然。写完核对字数，不能少于80字，也不能超过140字。'
                    '先回看原文明确写下的事情，再接住原文明确表达的感受，留出不必马上解释或回答的空间。'
                    '严禁增加原文没有的环境、天气、动作、经历或感受；不得把想做的事情说成已经发生。'
                    '情绪未明确时保持留白，不用能感受到、能想象到等话替用户推断。很短的记录可以承认信息有限，'
                    '说明记录可暂时停在这里、无需补全缘由，但不假设用户没有力气、压抑或情绪沉重。'
                    '不说建议你、你应该，不强行积极，不评分，不判断心理健康状态，不声称看过图片视频。'
                    'question可为空，或给一个简短、可选择回答的问题，只邀请回看，不追问用户是否行动或要求改变。')
            try:
                async with asyncio.timeout(110):
                    try:
                        result = await generate_json(instruction, source, ReviewText)
                    except HTTPException as exc:
                        # One bounded format repair, only for schema errors, within the
                        # same explicit user action. Never substitute a canned response.
                        cause = exc.__cause__
                        if not isinstance(cause, ValidationError):
                            raise
                        issues = [{'field': '.'.join(map(str, e['loc'])) or 'response', 'type': e['type']}
                                  for e in cause.errors(include_input=False, include_context=False)]
                        result = await generate_json(instruction +
                            '\n上一稿未通过格式检查：' + json.dumps(issues, ensure_ascii=False) +
                            '。请重新组织，body控制在100—120字，仍然只依据原文，不增加经历或情绪。', source, ReviewText)
            except Exception as exc:
                raise HTTPException(503, FAILURE) from exc
            # Lock only while checking the latest saved version and writing the cache.
            await self.db.execute(select(Diary.id).where(Diary.id == diary_id, Diary.user_id == user_id).with_for_update())
            self.db.expire_all()
            current = await self.insights._load_diary(diary_id, user_id)
            if content_version(source_text(current)) != view.content_version:
                raise HTTPException(409, '日记内容已更新，这次回顾没有保存，请再试一次。')
            if current.multimodal_insight is None:
                await self.insights.analyze_diary(diary_id, user_id=user_id)
            insight = (await self.db.execute(select(DiaryMultimodalInsight)
                       .where(DiaryMultimodalInsight.diary_id == diary_id).with_for_update()
                       .execution_options(populate_existing=True))).scalar_one()
            cache = {'body': encrypt_field(result.body), 'question': encrypt_field(result.question),
                     'generated_at': datetime.now(timezone.utc).isoformat(), 'content_version': view.content_version}
            insight.fusion_analysis = {**(insight.fusion_analysis or {}), CACHE_KEY: cache}
            await self.db.commit()
            self.db.expire_all()
            return await self.read(diary_id, user_id)
