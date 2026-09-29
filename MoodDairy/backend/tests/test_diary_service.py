"""Tests for DiaryService."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import ValidationError


class TestDiaryServiceInit:
    def test_service_can_be_imported(self):
        from app.services.diary_service import DiaryService

        assert DiaryService is not None


class TestCreateDiary:
    def test_create_diary_request_rejects_future_date(self):
        from app.models.schemas import CreateDiaryRequest

        with pytest.raises(ValidationError):
            CreateDiaryRequest(
                user_id=1,
                title="future diary",
                content="should fail",
                diary_date=date.today() + timedelta(days=1),
            )

    @pytest.mark.asyncio
    async def test_create_diary_success(self, mock_db_session, sample_create_diary_data):
        from app.models.schemas import CreateDiaryRequest
        from app.services.diary_service import DiaryService

        request = CreateDiaryRequest(**sample_create_diary_data)

        mock_result = MagicMock()
        mock_result.scalar_one = MagicMock(
            return_value=MagicMock(id=1, media_items=[], user_id=1, content=sample_create_diary_data["content"])
        )
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)

        with patch.object(service, "_trigger_extraction_async", MagicMock()):
            await service.create_diary(request)

        mock_db_session.add.assert_called()
        mock_db_session.commit.assert_called()

    @pytest.mark.asyncio
    async def test_create_diary_calculates_word_count(self, mock_db_session):
        from app.models.schemas import CreateDiaryRequest
        from app.services.diary_service import DiaryService

        request = CreateDiaryRequest(
            user_id=1,
            title="test",
            content="this is diary content for word counting",
            diary_date=date(2026, 1, 18),
        )

        mock_result = MagicMock()
        mock_result.scalar_one = MagicMock(
            return_value=MagicMock(id=1, media_items=[], user_id=1, content=request.content)
        )
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)

        with patch.object(service, "_trigger_extraction_async", MagicMock()):
            await service.create_diary(request)

        added_diary = mock_db_session.add.call_args.args[0]
        assert added_diary.word_count == len(request.content)


class TestExtractionTrigger:
    def test_trigger_extraction_async_uses_queue_when_available(self, mock_db_session):
        from app.services.diary_service import DiaryService

        service = DiaryService(mock_db_session)
        service._launch_extraction_worker = MagicMock()

        queued_worker = MagicMock()
        local_worker = MagicMock()

        with patch("app.celery_app.extraction_queue_available", return_value=True), \
            patch("app.celery_app.trigger_extraction", queued_worker), \
            patch("app.celery_app._run_extraction", local_worker):
            service._trigger_extraction_async(diary_id=7, force=False)

        service._launch_extraction_worker.assert_called_once_with(queued_worker, 7, False)

    def test_trigger_extraction_async_falls_back_to_local_worker(self, mock_db_session):
        from app.services.diary_service import DiaryService

        service = DiaryService(mock_db_session)
        service._launch_extraction_worker = MagicMock()

        queued_worker = MagicMock()
        local_worker = MagicMock()

        with patch("app.celery_app.extraction_queue_available", return_value=False), \
            patch("app.celery_app.trigger_extraction", queued_worker), \
            patch("app.celery_app._run_extraction", local_worker):
            service._trigger_extraction_async(diary_id=7, force=True)

        service._launch_extraction_worker.assert_called_once_with(local_worker, 7, True)


class TestGetDiary:
    @pytest.mark.asyncio
    async def test_get_diary_by_id_found(self, mock_db_session, mock_diary):
        from app.services.diary_service import DiaryService

        mock_result = MagicMock()
        mock_result.unique = MagicMock(return_value=mock_result)
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_diary)
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)
        result = await service.get_diary_by_id(diary_id=1)

        assert result is not None
        assert result.id == 1

    @pytest.mark.asyncio
    async def test_get_diary_by_id_not_found(self, mock_db_session):
        from app.services.diary_service import DiaryService

        mock_result = MagicMock()
        mock_result.unique = MagicMock(return_value=mock_result)
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)
        result = await service.get_diary_by_id(diary_id=999)

        assert result is None


class TestGetDiaryByDate:
    @pytest.mark.asyncio
    async def test_get_diary_by_date(self, mock_db_session, mock_diary):
        from app.services.diary_service import DiaryService

        mock_result = MagicMock()
        mock_result.unique = MagicMock(return_value=mock_result)
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_diary)
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)
        result = await service.get_diary_by_date(user_id=1, diary_date=date(2026, 1, 18))

        assert result is not None


class TestUpdateDiary:
    @pytest.mark.skip(reason="requires a more complete extraction service setup")
    @pytest.mark.asyncio
    async def test_update_diary_success(self, mock_db_session, mock_diary):
        from app.models.schemas import UpdateDiaryRequest
        from app.services.diary_service import DiaryService

        mock_diary.content = "original content"

        mock_result = MagicMock()
        mock_result.unique = MagicMock(return_value=mock_result)
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_diary)
        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)

        with patch.object(service, "_trigger_extraction_async", MagicMock()):
            update_data = UpdateDiaryRequest(title="updated title")
            await service.update_diary(diary_id=1, data=update_data)

        mock_db_session.commit.assert_called()


class TestDeleteDiary:
    @pytest.mark.asyncio
    async def test_delete_diary_success(self, mock_db_session, mock_diary):
        from app.services.diary_service import DiaryService

        diary_result = MagicMock()
        diary_result.scalar_one_or_none = MagicMock(return_value=mock_diary)

        media_result = MagicMock()
        media_result.all = MagicMock(return_value=[])

        delete_result = MagicMock()
        delete_result.rowcount = 1

        mock_db_session.execute = AsyncMock(
            side_effect=[diary_result, media_result, MagicMock(), delete_result]
        )

        service = DiaryService(mock_db_session)
        result = await service.delete_diary(diary_id=1, user_id=1)

        assert result is True
        mock_db_session.commit.assert_called_once()


class TestGetDatesWithDiary:
    @pytest.mark.asyncio
    async def test_get_dates_with_diary(self, mock_db_session):
        from app.services.diary_service import DiaryService

        mock_dates = [date(2026, 1, 15), date(2026, 1, 18)]

        mock_scalars = MagicMock()
        mock_scalars.all = MagicMock(return_value=mock_dates)

        mock_result = MagicMock()
        mock_result.scalars = MagicMock(return_value=mock_scalars)

        mock_db_session.execute = AsyncMock(return_value=mock_result)

        service = DiaryService(mock_db_session)
        result = await service.get_dates_with_diary(user_id=1, year=2026, month=1)

        assert len(result) == 2
        assert "2026-01-15" in result
        assert "2026-01-18" in result


class TestAggregatedMedia:
    @pytest.mark.asyncio
    async def test_get_diary_media_items_without_media_files_table(self, mock_db_session, mock_media_item):
        from app.services.diary_service import DiaryService

        service = DiaryService(mock_db_session)
        service._media_files_table_available = False

        mock_media_item.asset_id = 42
        mock_media_item.created_at = datetime(2026, 1, 18, 8, 0, 0)

        local_scalars = MagicMock()
        local_scalars.all = MagicMock(return_value=[mock_media_item])
        local_result = MagicMock()
        local_result.scalars = MagicMock(return_value=local_scalars)

        upload = MagicMock()
        upload.id = 42
        upload.file_type = "image"
        upload.file_path = "uploads/test.jpg"
        upload.thumbnail_path = None
        upload.file_size = 123
        upload.duration = None
        upload.mime_type = "image/jpeg"
        upload.created_at = datetime(2026, 1, 18, 8, 0, 0)

        upload_scalars = MagicMock()
        upload_scalars.all = MagicMock(return_value=[upload])
        upload_result = MagicMock()
        upload_result.scalars = MagicMock(return_value=upload_scalars)

        mock_db_session.execute = AsyncMock(side_effect=[local_result, upload_result])

        with patch("app.services.media_service.MediaService.get_file_url", side_effect=lambda path: f"/{path}"):
            items = await service.get_diary_media_items(1)

        assert len(items) == 1
        assert items[0].storage_mode == "local"
        assert items[0].url == "/uploads/test.jpg"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
