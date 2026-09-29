"""Tests for drawing schemas."""

import pytest
from pydantic import ValidationError

from app.drawing.schemas import (
    DEFAULT_DRAWING_SIZE,
    DrawingEnhanceRequest,
    DrawingFromDiaryRequest,
    DrawingGenerateRequest,
    DrawingResponse,
    SketchUploadResponse,
    VideoGenerateRequest,
    VideoStatusResponse,
    VideoTaskResponse,
    VideoTransformRequest,
)


class TestDrawingGenerateRequest:
    def test_valid_request(self):
        request = DrawingGenerateRequest(
            prompt="beautiful sunset by the sea",
            size=DEFAULT_DRAWING_SIZE,
            watermark=False,
        )
        assert request.prompt == "beautiful sunset by the sea"
        assert request.size == DEFAULT_DRAWING_SIZE
        assert request.watermark is False

    def test_default_values(self):
        request = DrawingGenerateRequest(prompt="test")
        assert request.size == DEFAULT_DRAWING_SIZE
        assert request.watermark is False

    def test_empty_prompt_rejected(self):
        with pytest.raises(ValidationError):
            DrawingGenerateRequest(prompt="")


class TestDrawingEnhanceRequest:
    def test_valid_request(self):
        request = DrawingEnhanceRequest(
            image_url="https://example.com/image.png",
            prompt="watercolor finish",
        )
        assert request.image_url == "https://example.com/image.png"
        assert request.prompt == "watercolor finish"

    def test_default_values(self):
        request = DrawingEnhanceRequest(image_url="https://example.com/image.png")
        assert request.prompt == ""
        assert request.size == DEFAULT_DRAWING_SIZE
        assert request.watermark is False


class TestSketchUploadResponse:
    def test_valid_response(self):
        response = SketchUploadResponse(image_url="https://example.com/sketch.png")
        assert response.image_url == "https://example.com/sketch.png"


class TestDrawingFromDiaryRequest:
    def test_default_values(self):
        request = DrawingFromDiaryRequest()
        assert request.style == "watercolor"
        assert request.include_emotion_transform is True


class TestDrawingResponse:
    def test_valid_response(self):
        response = DrawingResponse(
            image_url="https://example.com/generated.png",
            prompt_used="test prompt",
        )
        assert response.image_url == "https://example.com/generated.png"
        assert response.prompt_used == "test prompt"


class TestVideoGenerateRequest:
    def test_duration_range(self):
        assert VideoGenerateRequest(
            image_url="https://example.com/image.png",
            prompt="animate",
            duration=3,
        ).duration == 3
        assert VideoGenerateRequest(
            image_url="https://example.com/image.png",
            prompt="animate",
            duration=10,
        ).duration == 10

    def test_duration_out_of_range_rejected(self):
        with pytest.raises(ValidationError):
            VideoGenerateRequest(
                image_url="https://example.com/image.png",
                prompt="animate",
                duration=2,
            )


class TestVideoTransformRequest:
    def test_default_values(self):
        request = VideoTransformRequest(image_url="https://example.com/image.png")
        assert request.transformation_type == "negative_to_positive"
        assert request.duration == 5


class TestVideoTaskResponse:
    def test_valid_response(self):
        response = VideoTaskResponse(task_id="task_123", status="pending")
        assert response.task_id == "task_123"
        assert response.status == "pending"


class TestVideoStatusResponse:
    def test_success_status(self):
        response = VideoStatusResponse(
            task_id="task_123",
            status="succeeded",
            video_url="https://example.com/video.mp4",
        )
        assert response.video_url == "https://example.com/video.mp4"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
