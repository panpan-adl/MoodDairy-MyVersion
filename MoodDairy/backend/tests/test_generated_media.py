import io
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from PIL import Image

from app.drawing import generated_media as media


def jpeg():
    stream = io.BytesIO()
    Image.new("RGB", (8, 8), "green").save(stream, "JPEG")
    return stream.getvalue()


def test_backup_survives_signature_change(tmp_path, monkeypatch):
    monkeypatch.setattr(media.settings, "upload_dir", str(tmp_path))
    original = "https://provider.example/image.jpg?signature=old"
    path = media.save_image_backup(original, jpeg())
    assert path.read_bytes() == jpeg()
    assert path == media.image_backup_path(original.replace("old", "new"))


def test_error_document_is_not_published(tmp_path, monkeypatch):
    monkeypatch.setattr(media.settings, "upload_dir", str(tmp_path))
    with pytest.raises(Exception):
        media.save_image_backup("https://provider.example/image.jpg", b'{"Code":"AccessDenied"}')
    assert not list(tmp_path.rglob("*.jpg"))


@pytest.mark.asyncio
async def test_generation_publishes_archived_url(tmp_path, monkeypatch):
    monkeypatch.setattr(media.settings, "upload_dir", str(tmp_path))
    response = httpx.Response(200, content=jpeg(), request=httpx.Request("GET", "https://provider.example/image.jpg"))
    client = AsyncMock()
    client.get.return_value = response
    service = MagicMock()
    service.upload_media_bytes = AsyncMock(return_value={"url": "https://storage.example/permanent.jpg"})
    with patch.object(media.httpx, "AsyncClient") as factory, patch.object(media, "MediaService", return_value=service):
        factory.return_value.__aenter__.return_value = client
        url = await media.persist_generated_image("https://provider.example/image.jpg?expires=tomorrow")
    assert url == "https://storage.example/permanent.jpg"
    assert len(list(tmp_path.rglob("*.jpg"))) == 1


@pytest.mark.asyncio
async def test_failed_download_does_not_return_temporary_url():
    client = AsyncMock()
    client.get.side_effect = httpx.ConnectError("offline")
    with patch.object(media.httpx, "AsyncClient") as factory:
        factory.return_value.__aenter__.return_value = client
        with pytest.raises(httpx.ConnectError):
            await media.persist_generated_image("https://provider.example/image.jpg")
