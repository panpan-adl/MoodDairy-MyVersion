from app.services.emotion_orchestration_service import EmotionOrchestrationService


class Summary:
    def __init__(self, emotion: str, score: int):
        self.primary_emotion = emotion
        self.emotion_score = score


def test_current_low_message_triggers_bundle():
    result = EmotionOrchestrationService().evaluate("最近几天都很难受，什么也不想做", [])

    assert result.signal.state == "low"
    assert result.bundle is not None
    assert any(action.action == "play_white_noise" for action in result.bundle.auto_actions)


def test_sustained_low_summaries_trigger_bundle():
    summaries = [
        Summary("低落", 40),
        Summary("平静", 70),
        Summary("焦虑", 42),
        Summary("开心", 80),
        Summary("中性", 55),
    ]

    result = EmotionOrchestrationService().evaluate("还好", summaries)

    assert result.signal.state == "sustained_low"
    assert "2 条" in result.signal.trigger_reason
    assert result.bundle is not None


def test_declining_scores_trigger_bundle():
    summaries = [
        Summary("平静", 50),
        Summary("开心", 70),
    ]

    result = EmotionOrchestrationService().evaluate("今天一般", summaries)

    assert result.signal.state == "declining"
    assert result.signal.emotion_score == 50
    assert result.bundle is not None


def test_normal_chat_does_not_emit_bundle():
    summaries = [
        Summary("开心", 80),
        Summary("平静", 75),
    ]

    result = EmotionOrchestrationService().evaluate("今天看到一件有趣的事", summaries)

    assert result.signal.state == "normal"
    assert result.bundle is None
    assert not result.should_emit


def test_crisis_message_excludes_drawing_and_video_actions():
    result = EmotionOrchestrationService().evaluate("我不想活了，想伤害自己", [])

    assert result.signal.is_crisis is True
    assert result.bundle is not None
    actions = [service.action for service in result.bundle.services]
    assert "open_drawing" not in actions
    assert "play_white_noise" in actions
