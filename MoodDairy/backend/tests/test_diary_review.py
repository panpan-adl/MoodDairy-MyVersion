"""Real HTTP/auth/JSON persistence with synthetic diaries and a controlled model."""
import asyncio
from datetime import date
from unittest.mock import AsyncMock

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from sqlalchemy import BigInteger, ARRAY
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker

from app.database import get_db
from app.models.database import User, Diary, DiaryMedia, VoiceTranscription, DiarySummary, DiaryMultimodalInsight, MediaFile
from app.routers.insights import router
from app.security.deps import get_current_user_id
from app.security.field_crypto import encrypt_field
from app.services import diary_review_service as service
from app.utils.error_handler import AppError


@compiles(JSONB, 'sqlite')
@compiles(ARRAY, 'sqlite')
def json_type(type_, compiler, **kw):
    return 'JSON'


@compiles(BigInteger, 'sqlite')
def integer_type(type_, compiler, **kw):
    return 'INTEGER'


@pytest_asyncio.fixture
async def env(tmp_path, monkeypatch):
    engine = create_async_engine('sqlite+aiosqlite:///' + str(tmp_path / 'review.db'))
    tables = [m.__table__ for m in (User, Diary, DiaryMedia, VoiceTranscription, DiarySummary, DiaryMultimodalInsight, MediaFile)]
    async with engine.begin() as conn:
        await conn.run_sync(lambda c: User.metadata.create_all(c, tables=tables))
    maker = async_sessionmaker(engine, expire_on_commit=False)
    async with maker() as db:
        db.add_all([User(id=1, username='review-owner', password='x'), User(id=2, username='other', password='x'),
                    Diary(id=10, user_id=1, title=encrypt_field('实验报告'), content=encrypt_field('今天完成了机器学习实验报告，累但松了一口气。'), diary_date=date.today())])
        await db.commit()
    model = AsyncMock(return_value=service.ReviewText(body='你写下完成机器学习实验报告的这一天，累和松了一口气同时出现。这份记录把忙完后的感受留了下来，你不必急着给它一个单一的名字。此刻可以只是回看自己经历的这一段，让疲惫和轻松都在这里有一点位置。', question='写完报告后，哪一刻让你松了口气？'))
    monkeypatch.setattr(service, 'generate_json', model)
    app = FastAPI()
    from fastapi.responses import JSONResponse
    @app.exception_handler(AppError)
    async def handle(request, exc):
        return JSONResponse(status_code=exc.status_code, content={'detail': exc.message})
    app.include_router(router)
    async def session():
        async with maker() as db:
            try:
                yield db
            except Exception:
                await db.rollback()
                raise
    owner = [1]
    app.dependency_overrides[get_db] = session
    app.dependency_overrides[get_current_user_id] = lambda: owner[0]
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as c:
        yield c, maker, owner, model
    await engine.dispose()


URL = '/insights/diaries/10/review'


@pytest.mark.asyncio
async def test_explicit_generation_encrypted_cache_and_refresh_preservation(env):
    c, maker, owner, model = env
    initial = (await c.get(URL)).json()
    assert initial['body'] is None and initial['has_text']
    model.assert_not_awaited()
    result = await c.post(URL, json={'content_version': initial['content_version']})
    assert result.status_code == 200, result.text
    value = result.json()
    assert value['body'] and value['question'] and value['generated_at'] and not value['stale']
    async with maker() as db:
        from sqlalchemy import select
        row = (await db.execute(select(DiaryMultimodalInsight))).scalar_one()
        cache = row.fusion_analysis[service.CACHE_KEY]
        assert cache['body'].startswith('enc:v1:') and value['body'] not in str(row.fusion_analysis)
    assert (await c.get(URL)).json() == value
    await c.post(URL, json={})
    assert model.await_count == 1
    assert (await c.post('/insights/diaries/10/analyze')).status_code == 200
    assert (await c.get(URL)).json()['body'] == value['body']
    assert (await c.post(URL, json={'refresh': True})).status_code == 200
    assert model.await_count == 2
    owner[0] = 2
    assert (await c.get(URL)).status_code == 404
    assert (await c.post(URL, json={})).status_code == 404
    assert model.await_count == 2


@pytest.mark.asyncio
async def test_edits_mark_stale_and_failures_keep_previous_review(env):
    c, maker, _, model = env
    old = (await c.post(URL, json={})).json()
    async with maker() as db:
        diary = await db.get(Diary, 10)
        diary.content = encrypt_field('下午很疲惫，报告还没写完。')
        await db.commit()
    stale = (await c.get(URL)).json()
    assert stale['stale'] and stale['body'] == old['body']
    assert (await c.post(URL, json={'content_version': old['content_version']})).status_code == 409
    model.side_effect = RuntimeError('provider unavailable')
    failed = await c.post(URL, json={'refresh': True})
    assert failed.status_code == 503 and failed.json()['detail'] == service.FAILURE
    assert (await c.get(URL)).json()['body'] == old['body']


@pytest.mark.asyncio
async def test_attachments_never_enter_prompt_or_supply_emotion_scores(env):
    c, maker, _, model = env
    async with maker() as db:
        diary = await db.get(Diary, 10)
        diary.title = ''
        diary.content = ''
        db.add_all([DiaryMedia(id=11, diary_id=10, media_type='image', content='private image detail', media_url='secret'),
                    DiaryMedia(id=12, diary_id=10, media_type='video', content='private video detail')])
        await db.commit()
    value = (await c.post(URL, json={})).json()
    assert not value['has_text'] and not value['clues'] and value['body'] is None
    assert value['attachment_note'] == '这篇日记还留下了 1 张照片、1 段视频'
    model.assert_not_awaited()
    async with maker() as db:
        db.add(VoiceTranscription(id=31, diary_id=10, original_text=encrypt_field('今天有点累。'), detected_emotion='疲惫', emotion_score=None))
        await db.commit()
    value = (await c.post(URL, json={})).json()
    assert value['has_text'] and any('语音转写：今天有点累。' == clue for clue in value['clues'])
    context = model.call_args.args[1]
    assert context['transcripts'] == ['今天有点累。']
    assert 'secret' not in str(context) and 'private' not in str(context) and 'image' not in str(context)
    assert all('/100' not in clue for clue in value['clues'])


@pytest.mark.asyncio
async def test_repeated_click_and_edit_during_generation_do_not_publish_old_version(env):
    c, maker, _, model = env
    started, finish = asyncio.Event(), asyncio.Event()
    answer = model.return_value
    async def pending(*args):
        started.set()
        await finish.wait()
        return answer
    model.side_effect = pending
    request = asyncio.create_task(c.post(URL, json={}))
    await started.wait()
    assert (await c.post(URL, json={})).status_code == 409
    async with maker() as db:
        diary = await db.get(Diary, 10)
        diary.content = encrypt_field('刚刚又改了日记。')
        await db.commit()
    finish.set()
    assert (await request).status_code == 409
    assert (await c.get(URL)).json()['body'] is None
    assert model.await_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('text', ['今天和朋友散步，很开心。', '累，不想说话。', '完成了报告但还是担心明天。', '烦。'])
async def test_input_is_the_target_saved_diary_only(env, text):
    c, maker, _, model = env
    async with maker() as db:
        diary = await db.get(Diary, 10)
        diary.content = encrypt_field(text)
        db.add(Diary(id=99, user_id=1, content='不能读取的另一篇日记', diary_date=date.today()))
        await db.commit()
    assert (await c.post(URL, json={})).status_code == 200
    context = model.call_args.args[1]
    assert context['body'] == text and '另一篇' not in str(context)


@pytest.mark.asyncio
@pytest.mark.parametrize('kind', ['text', 'transcript'])
async def test_text_blocks_and_transcript_edits_invalidate_cache(env, kind):
    c, maker, _, model = env
    await c.post(URL, json={})
    async with maker() as db:
        if kind == 'text':
            db.add(DiaryMedia(diary_id=10, media_type='text', content=encrypt_field('新增加的文字块。')))
        else:
            db.add(VoiceTranscription(diary_id=10, processed_text=encrypt_field('更新后的语音转写。')))
        await db.commit()
    assert (await c.get(URL)).json()['stale']
    assert model.await_count == 1
    assert (await c.post(URL, json={'refresh': True})).status_code == 200
    assert not (await c.get(URL)).json()['stale']


def test_legacy_fallback_scores_and_unproven_emotions_are_hidden():
    from types import SimpleNamespace as Obj
    diary = Obj(id=10, title='', content='今天有些累。', media_items=[], multimodal_insight=None,
                summary=Obj(primary_emotion='中性', emotion_score=50, emotion_analysis={'key_sentences': []}),
                voice_transcriptions=[Obj(id=1, original_text='今天有些累。', processed_text='',
                                          detected_emotion='中性', emotion_score=50, confidence=0)])
    clues = service.review_view(diary).clues
    assert not any('/100' in clue or '中性' in clue for clue in clues)
    diary.summary.emotion_analysis = {'key_sentences': ['今天有些累。'], 'emotion_score': 32}
    diary.summary.primary_emotion, diary.summary.emotion_score = '疲惫', 32
    assert any('32/100' in clue and '来源' in clue for clue in service.review_view(diary).clues)


def test_model_response_requires_length_and_at_most_one_optional_question():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        service.ReviewText(body='太短')
    with pytest.raises(ValidationError):
        service.ReviewText(body='回顾' * 45, question='为什么？然后呢？')


def test_insight_service_is_safe_on_first_request_after_cold_start():
    import subprocess
    import sys
    result = subprocess.run([sys.executable, '-c',
        'from app.services.diary_review_service import DiaryReviewService; '
        'from app.models.database import Diary; DiaryReviewService(None); '
        'assert hasattr(Diary, "summary") and hasattr(Diary, "multimodal_insight")'],
        capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr


@pytest.mark.asyncio
async def test_schema_repair_is_bounded_and_not_used_for_network_failures(env):
    from fastapi import HTTPException
    from pydantic import ValidationError
    c, maker, _, model = env
    answer = model.return_value
    try:
        service.ReviewText(body='短')
    except ValidationError as cause:
        invalid = HTTPException(503, 'invalid schema')
        invalid.__cause__ = cause
    model.side_effect = [invalid, answer]
    assert (await c.post(URL, json={})).status_code == 200
    assert model.await_count == 2
    model.reset_mock()
    model.side_effect = HTTPException(503, 'network error')
    assert (await c.post(URL, json={'refresh': True})).status_code == 503
    assert model.await_count == 1
    assert (await c.get(URL)).json()['body'] == answer.body
