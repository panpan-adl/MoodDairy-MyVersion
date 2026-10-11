"""
摄像头表情识别上报路由测试

测试 /face-emotion/report 端点：正常上报、非法 emotion 校验、未登录 401
"""
import json
from datetime import datetime, timezone

import pytest
from pydantic import ValidationError


class TestFaceEmotionRouterConfiguration:
    """测试路由配置"""

    def test_router_can_be_imported(self):
        """测试路由可以导入"""
        from app.routers.face_emotion import router
        assert router is not None

    def test_router_prefix(self):
        """测试路由前缀"""
        from app.routers.face_emotion import router
        assert router.prefix == "/face-emotion"

    def test_router_tags(self):
        """测试路由标签"""
        from app.routers.face_emotion import router
        assert "face-emotion" in router.tags

    def test_report_endpoint_exists(self):
        """测试上报端点存在且为 POST"""
        from app.routers.face_emotion import router

        matched = [route for route in router.routes if route.path == "/face-emotion/report"]
        assert matched
        assert "POST" in matched[0].methods


class TestFaceEmotionReportValidation:
    """测试请求体校验（对应 HTTP 422）"""

    def test_invalid_emotion_rejected(self):
        """非法 emotion 标签应校验失败"""
        from app.routers.face_emotion import FaceEmotionReportRequest

        with pytest.raises(ValidationError):
            FaceEmotionReportRequest(
                emotion="excited",
                confidence=0.9,
                captured_at=datetime.now(),
            )

    def test_confidence_out_of_range_rejected(self):
        """confidence 超出 0-1 范围应校验失败"""
        from app.routers.face_emotion import FaceEmotionReportRequest

        with pytest.raises(ValidationError):
            FaceEmotionReportRequest(
                emotion="happy",
                confidence=1.5,
                captured_at=datetime.now(),
            )

    def test_valid_emotions_accepted(self):
        """全部 7 类合法表情应通过校验"""
        from app.routers.face_emotion import FaceEmotionReportRequest

        for emotion in ["angry", "disgust", "fear", "happy", "sad", "surprise", "neutral"]:
            request = FaceEmotionReportRequest(
                emotion=emotion,
                confidence=0.8,
                captured_at=datetime.now(),
            )
            assert request.emotion == emotion

    def test_timezone_aware_captured_at_normalized_to_naive_local(self):
        """App 发送的带 Z 的 UTC 时间（Instant.toString()）应转为 naive 本地时间"""
        from app.routers.face_emotion import FaceEmotionReportRequest

        utc_time = datetime(2026, 10, 2, 1, 7, 53, tzinfo=timezone.utc)
        request = FaceEmotionReportRequest(
            emotion="happy",
            confidence=0.92,
            captured_at=utc_time,
        )

        assert request.captured_at.tzinfo is None
        expected = utc_time.astimezone().replace(tzinfo=None)
        assert request.captured_at == expected

    def test_naive_captured_at_unchanged(self):
        """naive 时间保持不变"""
        from app.routers.face_emotion import FaceEmotionReportRequest

        naive = datetime(2026, 10, 2, 9, 7, 53)
        request = FaceEmotionReportRequest(
            emotion="happy",
            confidence=0.92,
            captured_at=naive,
        )

        assert request.captured_at == naive
        assert request.captured_at.tzinfo is None


class TestFaceEmotionReportEndpoint:
    """测试上报端点行为"""

    async def test_report_success(self, mock_db_session):
        """正常上报应写入 EmotionRecord 并返回 success"""
        from app.routers.face_emotion import (
            FaceEmotionReportRequest,
            report_face_emotion,
        )

        captured_at = datetime(2026, 9, 29, 12, 0, 0)
        payload = FaceEmotionReportRequest(
            emotion="happy",
            confidence=0.92,
            captured_at=captured_at,
        )

        response = await report_face_emotion(
            payload=payload,
            current_user_id=1,
            db=mock_db_session,
        )

        assert response.success is True
        assert response.message

        mock_db_session.add.assert_called_once()
        record = mock_db_session.add.call_args.args[0]
        assert record.user_id == 1
        assert record.source_type == "face_camera"
        assert record.source_id is None
        assert record.emotion_type == "happy"
        assert record.emotion_score == 80
        assert record.recorded_at == captured_at

        analysis = json.loads(record.analysis)
        assert analysis["confidence"] == pytest.approx(0.92)
        assert analysis["captured_at"] == captured_at.isoformat()

        mock_db_session.commit.assert_awaited_once()

    async def test_report_maps_negative_emotion_scores(self, mock_db_session):
        """负面表情应映射为低分"""
        from app.routers.face_emotion import (
            FaceEmotionReportRequest,
            report_face_emotion,
        )

        expected_scores = {"sad": 35, "angry": 30, "fear": 30, "disgust": 30}
        for emotion, score in expected_scores.items():
            mock_db_session.reset_mock()
            payload = FaceEmotionReportRequest(
                emotion=emotion,
                confidence=0.7,
                captured_at=datetime.now(),
            )
            response = await report_face_emotion(
                payload=payload,
                current_user_id=1,
                db=mock_db_session,
            )
            assert response.success is True
            record = mock_db_session.add.call_args.args[0]
            assert record.emotion_type == emotion
            assert record.emotion_score == score


class TestFaceEmotionReportAuth:
    """测试未登录访问"""

    async def test_report_requires_authentication(self):
        """未携带令牌时鉴权依赖应抛出 401"""
        from unittest.mock import MagicMock

        from fastapi import HTTPException

        from app.security.deps import get_current_user_id

        request = MagicMock()
        request.state.user_id = None
        request.headers = {}

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user_id(request=request, credentials=None)

        assert exc_info.value.status_code == 401


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
