from datetime import datetime
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.routers.face_emotion import FaceEmotionReportRequest, report_face_emotion
from app.services.emotion_orchestration_service import EmotionOrchestrationService


@pytest.mark.parametrize("emotion,confidence,duration", [
    ("sad", 0.8, 9_999), ("sad", 0.39, 10_000),
    ("neutral", 0.8, 10_000), ("happy", 0.8, 10_000),
])
def test_short_weak_or_positive_observation_does_not_prompt(emotion, confidence, duration):
    assert EmotionOrchestrationService().evaluate_sustained_face(emotion, confidence, duration) is None


@pytest.mark.asyncio
async def test_report_returns_recommendations_without_chat_and_without_autoplay():
    db = AsyncMock()
    db.add = MagicMock()
    response = await report_face_emotion(
        FaceEmotionReportRequest(emotion="sad", confidence=0.8,
                                 captured_at=datetime.now(), sustained_negative_ms=10_000),
        current_user_id=7, db=db,
    )
    assert response.success
    assert response.service_bundle["type"] == "service_bundle"
    assert response.service_bundle["auto_actions"] == []
    assert any(item["action"] == "open_relax" for item in response.service_bundle["services"])
    assert db.add.call_args.args[0].user_id == 7
    db.commit.assert_awaited_once()


@pytest.mark.asyncio
async def test_old_client_report_still_saves_without_prompt():
    db = AsyncMock()
    db.add = MagicMock()
    response = await report_face_emotion(
        FaceEmotionReportRequest(emotion="sad", confidence=0.8, captured_at=datetime.now()),
        current_user_id=7, db=db,
    )
    assert response.success
    assert response.service_bundle is None
