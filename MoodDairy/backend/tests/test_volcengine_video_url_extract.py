"""Regression tests for video URL extraction from Ark task results."""
from types import SimpleNamespace

import pytest

from app.drawing.volcengine_client import VolcengineClient

# Real SDK model (tested without requiring ARK_API_KEY)
try:
    from volcenginesdkarkruntime.types.content_generation.content_generation_task import Content
    _HAS_CONTENT = True
except ImportError:
    _HAS_CONTENT = False


@pytest.fixture
def extract_only() -> VolcengineClient:
    """Instance without hitting real API (no __init__)."""
    return VolcengineClient.__new__(VolcengineClient)


def test_extract_from_content_object_with_video_url(extract_only: VolcengineClient) -> None:
    content = SimpleNamespace(
        video_url="https://cdn.example.com/a.mp4",
        file_url="",
        last_frame_url="",
    )
    result = SimpleNamespace(content=content)
    assert extract_only._extract_video_url_from_result(result) == "https://cdn.example.com/a.mp4"


def test_extract_from_content_object_prefers_file_url_when_video_empty(extract_only: VolcengineClient) -> None:
    content = SimpleNamespace(
        video_url="",
        file_url="https://cdn.example.com/b.mp4",
        last_frame_url="",
    )
    result = SimpleNamespace(content=content)
    assert extract_only._extract_video_url_from_result(result) == "https://cdn.example.com/b.mp4"


def test_extract_from_content_string_url(extract_only: VolcengineClient) -> None:
    result = SimpleNamespace(content="https://cdn.example.com/c.mp4")
    assert extract_only._extract_video_url_from_result(result) == "https://cdn.example.com/c.mp4"


def test_no_false_positive_from_iterating_string_chars(extract_only: VolcengineClient) -> None:
    """Previously `for item in content` on a URL string walked per-character and never matched."""
    result = SimpleNamespace(content="https://volces.com/x/video.mp4")
    assert extract_only._extract_video_url_from_result(result) == "https://volces.com/x/video.mp4"


@pytest.mark.skipif(not _HAS_CONTENT, reason="volcenginesdkarkruntime not installed")
def test_extract_real_content_model_video_url(extract_only: VolcengineClient) -> None:
    """Test with the real Pydantic Content model returned by the Ark SDK."""
    content = Content(
        video_url="https://cdn.volces.com/video/abc.mp4",
        last_frame_url="",
        file_url="",
    )
    result = SimpleNamespace(status="succeeded", content=content)
    assert extract_only._extract_video_url_from_result(result) == "https://cdn.volces.com/video/abc.mp4"


@pytest.mark.skipif(not _HAS_CONTENT, reason="volcenginesdkarkruntime not installed")
def test_extract_real_content_model_file_url_fallback(extract_only: VolcengineClient) -> None:
    """Empty video_url should fall back to file_url."""
    content = Content(
        video_url="",
        last_frame_url="",
        file_url="https://cdn.volces.com/video/def.mp4",
    )
    result = SimpleNamespace(status="succeeded", content=content)
    assert extract_only._extract_video_url_from_result(result) == "https://cdn.volces.com/video/def.mp4"
