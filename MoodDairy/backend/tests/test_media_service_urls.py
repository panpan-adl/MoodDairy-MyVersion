"""Focused tests for media URL handling."""

from pathlib import Path
from unittest.mock import patch


def test_get_file_url_preserves_absolute_urls():
    from app.services.media_service import MediaService

    with patch.object(Path, "mkdir"):
        service = MediaService(upload_dir="./uploads")

    assert service.get_file_url("https://example.com/file.jpg") == "https://example.com/file.jpg"
