"""Regression checks for failures found during the full functional audit."""
from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy import inspect
from sqlalchemy.orm.attributes import set_committed_value

from app.models.database import Diary
from app.security.field_crypto import encrypt_field, decrypt_diary_fields


def test_reading_encrypted_diary_does_not_make_plaintext_dirty():
    diary = Diary(id=1, user_id=1, diary_date=date.today())
    set_committed_value(diary, "title", encrypt_field("中文标题"))
    set_committed_value(diary, "content", encrypt_field("今天很开心"))
    decrypt_diary_fields(diary)
    assert diary.title == "中文标题"
    assert diary.content == "今天很开心"
    assert not inspect(diary).attrs.title.history.has_changes()
    assert not inspect(diary).attrs.content.history.has_changes()
    diary.is_highlight = 1
    assert not inspect(diary).attrs.content.history.has_changes()


@pytest.mark.asyncio
async def test_encrypted_search_matches_text_and_pages_after_filtering():
    from app.services.diary_service import DiaryService
    rows = [Diary(id=i, user_id=7, title=encrypt_field(title), content=encrypt_field("正文"),
                  diary_date=date.today(), media_items=[]) for i, title in
            [(1,"unrelated"),(2,"MATCH first"),(3,"match second")]]
    result = MagicMock()
    result.unique.return_value.scalars.return_value.all.return_value = rows
    db = AsyncMock()
    db.execute.return_value = result
    found = await DiaryService(db).search_diaries_by_text(7, "match", limit=1, offset=1)
    assert [x.id for x in found] == [3]
    assert found[0].title == "match second"
    statement = db.execute.call_args.args[0]
    assert statement.compile().params["user_id_1"] == 7


@pytest.mark.asyncio
async def test_upload_is_not_intercepted_by_static_media_server():
    from app.main import StaticMediaBypassMiddleware
    downstream = AsyncMock()
    server = StaticMediaBypassMiddleware(downstream)
    scope = {"type":"http","method":"POST","path":"/media/upload"}
    receive, send = AsyncMock(), AsyncMock()
    await server(scope, receive, send)
    downstream.assert_awaited_once_with(scope, receive, send)


@pytest.mark.asyncio
async def test_video_does_not_deliver_silent_source_when_mixing_fails():
    from app.drawing.video_service import VideoService
    client = MagicMock()
    client.get_video_task_status = AsyncMock(return_value={"status":"succeeded","video_url":"https://example.com/original.mp4"})
    failure=dict(status="failed", video_url=None, audio_status="failed", audio_error="配乐服务不可用", error="视频已生成，但背景音乐添加失败，请重试。")
    with patch("app.drawing.video_audio_mixer.background_music_status", return_value=failure):
        result = await VideoService(client).get_video_status("qa_video")
    assert result["status"] == "failed"
    assert result["audio_status"] == "failed"
    assert result["video_url"] is None


def test_local_extraction_runs_without_celery_and_disposes_engine():
    from app import celery_app
    engine = MagicMock()
    engine.dispose = AsyncMock()
    session = AsyncMock()
    sessions = MagicMock()
    sessions.return_value.__aenter__ = AsyncMock(return_value=session)
    sessions.return_value.__aexit__ = AsyncMock(return_value=None)
    extractor = MagicMock()
    extractor.extract_diary = AsyncMock(return_value={"status":"done"})
    with patch.dict("os.environ", {"DATABASE_URL":"postgresql+asyncpg://unused"}), \
         patch.object(celery_app,"create_async_engine",return_value=engine), \
         patch.object(celery_app,"sessionmaker",return_value=sessions), \
         patch("app.services.extraction_service.ExtractionService",return_value=extractor):
        assert celery_app._run_extraction(9, True) == {"status":"done"}
    extractor.extract_diary.assert_awaited_once_with(9, True)
    engine.dispose.assert_awaited_once()
