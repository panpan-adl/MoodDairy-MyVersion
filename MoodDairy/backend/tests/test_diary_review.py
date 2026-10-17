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
    monkeypatch.setenv("DIARY_KEYWORD_ENGINE", "textrank")
    engine = create_async_engine('sqlite+aiosqlite:///' + str(tmp_path / 'review.db'))
    tables = [m.__table__ for m in (User, Diary, DiaryMedia, VoiceTranscription, DiarySummary, DiaryMultimodalInsight, MediaFile)]
    async with engine.begin() as conn:
        await conn.run_sync(lambda c: User.metadata.create_all(c, tables=tables))
    maker = async_sessionmaker(engine, expire_on_commit=False)
    async with maker() as db:
        db.add_all([User(id=1, username='review-owner', password='x'), User(id=2, username='other', password='x'),
                    Diary(id=10, user_id=1, title=encrypt_field('实验报告'), content=encrypt_field('今天完成了机器学习实验报告，累但松了一口气。'), diary_date=date.today())])
        await db.commit()
    answer = {'body': '原文中的“累”和“松了一口气”留下了不同的感受。',
              'primary_emotion': '疲惫、安宁', 'keywords': [
                  {'text': '累', 'weight': .8, 'category': 'fatigue', 'kind': 'emotion'},
                  {'text': '松了一口气', 'weight': .7, 'category': 'calm', 'kind': 'emotion'}],
              'extraction_method': 'textrank+lexicon'}
    model = AsyncMock(return_value=answer)
    monkeypatch.setattr(service, 'run_in_threadpool', model)
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
    assert value['body'] and value['primary_emotion'] == '疲惫、安宁'
    assert value['question'] is None and value['generated_at'] and not value['stale']
    async with maker() as db:
        from sqlalchemy import select
        row = (await db.execute(select(DiaryMultimodalInsight))).scalar_one()
        cache = row.fusion_analysis[service.CACHE_KEY]
        assert cache['body'].startswith('enc:v1:') and value['body'] not in str(row.fusion_analysis)
        assert cache['primary_emotion'].startswith('enc:v1:')
        assert cache['analysis_version'] == service.ANALYSIS_VERSION
        assert cache['keywords'].startswith('enc:v1:')
        assert '松了一口气' not in str(row.fusion_analysis)
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
async def test_edits_mark_stale_and_model_failure_returns_real_offline_review(env):
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
    fallback = await c.post(URL, json={'refresh': True})
    assert fallback.status_code == 200, fallback.text
    value = fallback.json()
    assert value['keywords'] and value['extraction_method'] == 'source-phrases+lexicon'
    assert all(word['text'] in '下午很疲惫，报告还没写完。' for word in value['keywords'])
    assert not value['stale']
    assert (await c.get(URL)).json()['body'] == value['body']


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


def test_insight_service_is_safe_on_first_request_after_cold_start():
    import subprocess
    import sys
    result = subprocess.run([sys.executable, '-c',
        'from app.services.diary_review_service import DiaryReviewService; '
        'from app.models.database import Diary; DiaryReviewService(None); '
        'assert hasattr(Diary, "summary") and hasattr(Diary, "multimodal_insight")'],
        capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr


def test_old_reflection_is_hidden_and_requires_emotion_analysis():
    from types import SimpleNamespace as Obj
    source = {'title': '', 'body': '今天很开心。', 'text_blocks': [], 'transcripts': []}
    diary = Obj(id=10, title='', content=source['body'], media_items=[], voice_transcriptions=[],
                summary=None, multimodal_insight=Obj(fusion_analysis={service.CACHE_KEY: {
                    'body': encrypt_field('旧的行程复述'), 'content_version': service.content_version(source)}}))
    view = service.review_view(diary)
    assert view.stale and view.body is None and view.primary_emotion is None


@pytest.mark.asyncio
async def test_real_offline_extraction_http_round_trip(env, monkeypatch):
    import socket
    c, _, _, model = env
    def reject_network(*args, **kwargs):
        raise AssertionError('Offline extraction must never open a network connection')
    monkeypatch.setattr(socket, 'create_connection', reject_network)
    model.side_effect = lambda func, source: func(source)
    response = await c.post(URL, json={})
    assert response.status_code == 200, response.text
    value = response.json()
    assert value['keywords'] and value['extraction_method'] == 'textrank+lexicon'
    original = '实验报告今天完成了机器学习实验报告，累但松了一口气。'
    assert all(word['text'] in original for word in value['keywords'])
    assert (await c.get(URL)).json() == value


@pytest.mark.asyncio
async def test_slow_cold_model_returns_basic_keywords_instead_of_failure(env, monkeypatch):
    c, _, _, model = env
    monkeypatch.setattr(service, 'EXTRACTION_TIMEOUT_SECONDS', .01)
    async def slow_model(*args):
        await asyncio.sleep(10)
    model.side_effect = slow_model
    response = await c.post(URL, json={})
    assert response.status_code == 200
    value = response.json()
    assert value['keywords'] and value['extraction_method'] == 'source-phrases+lexicon'
    assert (await c.get(URL)).json() == value
