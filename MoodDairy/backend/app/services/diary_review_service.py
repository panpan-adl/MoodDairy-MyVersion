"""Explicit, owner-scoped diary reflection. Media bytes never enter this flow."""
import asyncio
import hashlib
import json
import weakref
from datetime import datetime, timezone

from fastapi import HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.models.database import Diary, DiaryMultimodalInsight
from app.security.field_crypto import decrypt_field, encrypt_field
from app.services.multimodal_insight_service import MultimodalInsightService
from starlette.concurrency import run_in_threadpool
from app.services.diary_keyword_service import extract_review, extract_basic_review

CACHE_KEY = 'gentle_review'
FAILURE = '这次没能整理出来，你写下的内容还在，可以稍后再试。'
MEDIA_ONLY = '添加一些文字后，可以分析这篇日记表达的情绪。'
ANALYSIS_VERSION = 3
EXTRACTION_TIMEOUT_SECONDS = 8
_locks = weakref.WeakValueDictionary()


class ReviewKeyword(BaseModel):
    text: str
    weight: float = Field(ge=0, le=1)
    category: str = 'neutral'
    kind: str = 'keyword'


class DiaryReviewResponse(BaseModel):
    diary_id: int
    content_version: str
    body: str | None = None
    primary_emotion: str | None = None
    question: str | None = None
    generated_at: str | None = None
    stale: bool = False
    has_text: bool
    attachment_note: str | None = None
    clues: list[str] = Field(default_factory=list)
    keywords: list[ReviewKeyword] = Field(default_factory=list)
    extraction_method: str | None = None


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
    current_analysis = cache.get('analysis_version') == ANALYSIS_VERSION
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
                              body=decrypt_field(cache.get('body')) if current_analysis else None,
                              primary_emotion=decrypt_field(cache.get('primary_emotion')) if current_analysis else None,
                              question=None,
                              generated_at=cache.get('generated_at'),
                              stale=bool(cache and (cache.get('content_version') != version or not current_analysis)),
                              has_text=has_text(source), attachment_note=note, clues=clues,
                              keywords=json.loads(decrypt_field(cache.get('keywords')) or '[]') if current_analysis else [],
                              extraction_method=cache.get('extraction_method') if current_analysis else None)


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
            try:
                async with asyncio.timeout(EXTRACTION_TIMEOUT_SECONDS):
                    result = await run_in_threadpool(extract_review, source)
            except Exception:
                result = extract_basic_review(source)
            if not result['keywords']:
                result = extract_basic_review(source)
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
            cache = {'body': encrypt_field(result['body']), 'question': None,
                     'primary_emotion': encrypt_field(result['primary_emotion']), 'analysis_version': ANALYSIS_VERSION,
                     'keywords': encrypt_field(json.dumps(result['keywords'], ensure_ascii=False)),
                     'extraction_method': result['extraction_method'],
                     'generated_at': datetime.now(timezone.utc).isoformat(), 'content_version': view.content_version}
            insight.fusion_analysis = {**(insight.fusion_analysis or {}), CACHE_KEY: cache}
            await self.db.commit()
            self.db.expire_all()
            return await self.read(diary_id, user_id)
