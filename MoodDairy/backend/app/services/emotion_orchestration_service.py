"""Emotion-aware service orchestration for chat turns."""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Dict, Iterable, List, Optional


NEGATIVE_EMOTIONS = {
    "低落",
    "焦虑",
    "愤怒",
    "悲伤",
    "难过",
    "沮丧",
    "痛苦",
    "孤独",
    "疲惫",
    "sad",
    "anxious",
    "angry",
    "depressed",
    "lonely",
    "tired",
}

NEGATIVE_TEXT_PATTERNS = (
    "低落",
    "难受",
    "难过",
    "崩溃",
    "压抑",
    "焦虑",
    "烦躁",
    "痛苦",
    "撑不住",
    "不想做",
    "什么也不想",
    "很累",
    "太累",
    "孤独",
    "失眠",
    "没意义",
    "sad",
    "anxious",
    "depressed",
    "hopeless",
)

CRISIS_TEXT_PATTERNS = (
    "自杀",
    "自残",
    "轻生",
    "结束生命",
    "不想活",
    "活不下去",
    "伤害自己",
    "suicide",
    "kill myself",
    "self harm",
)

FACE_NEGATIVE_EMOTIONS = {"sad", "angry", "fear", "disgust"}
FACE_SUSTAINED_LOW_THRESHOLD = 3


@dataclass(frozen=True)
class EmotionSignal:
    state: str
    emotion_type: str
    emotion_score: int
    trigger_reason: str
    is_crisis: bool = False

    def to_event(self) -> Dict[str, Any]:
        return {
            "type": "emotion_signal",
            "emotion_state": self.state,
            "emotion_type": self.emotion_type,
            "emotion_score": self.emotion_score,
            "trigger_reason": self.trigger_reason,
            "is_crisis": self.is_crisis,
        }


@dataclass(frozen=True)
class ServiceAction:
    id: str
    title: str
    description: str
    action: str
    payload: Dict[str, Any] = field(default_factory=dict)
    auto_start: bool = False

    def to_payload(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "description": self.description,
            "action": self.action,
            "payload": self.payload,
            "auto_start": self.auto_start,
        }


@dataclass(frozen=True)
class ServiceBundle:
    bundle_id: str
    title: str
    message: str
    trigger_reason: str
    services: List[ServiceAction]
    auto_actions: List[ServiceAction]
    is_crisis: bool = False

    def to_event(self) -> Dict[str, Any]:
        return {
            "type": "service_bundle",
            "bundle_id": self.bundle_id,
            "title": self.title,
            "message": self.message,
            "trigger_reason": self.trigger_reason,
            "is_crisis": self.is_crisis,
            "services": [service.to_payload() for service in self.services],
            "auto_actions": [action.to_payload() for action in self.auto_actions],
        }


@dataclass(frozen=True)
class EmotionOrchestrationResult:
    signal: EmotionSignal
    bundle: Optional[ServiceBundle] = None

    @property
    def should_emit(self) -> bool:
        return self.signal.state != "normal"


class EmotionOrchestrationService:
    """Small deterministic classifier for low-mood service recommendations."""

    def evaluate_sustained_face(self, emotion: str, confidence: float, duration_ms: int) -> Optional[ServiceBundle]:
        """React to continuous local observations without waiting for a chat message."""
        if emotion not in FACE_NEGATIVE_EMOTIONS or confidence < 0.40 or duration_ms < 10_000:
            return None
        signal = EmotionSignal(
            state="sustained_low",
            emotion_type=emotion,
            emotion_score=35,
            trigger_reason=f"连续识别到约 {duration_ms // 1000} 秒负面表情",
        )
        return self._build_service_bundle(signal)

    def evaluate(
        self,
        message: str,
        summaries: Iterable[Any],
        face_signals: Optional[Iterable[Any]] = None,
    ) -> EmotionOrchestrationResult:
        current_negative = self._message_is_negative(message)
        current_score = 40 if current_negative else 60
        current_emotion = self._infer_message_emotion(message) if current_negative else "中性"
        is_crisis = self._message_is_crisis(message)

        recent = list(summaries or [])[:5]
        negative_summaries = [item for item in recent if self._summary_is_negative(item)]
        decline = self._recent_decline(recent)

        negative_face_count = 0
        latest_face_emotion: Optional[str] = None
        if face_signals:
            face_items = [item for item in face_signals if self._face_signal_is_negative(item)]
            negative_face_count = len(face_items)
            latest_face_emotion = self._face_signal_emotion(face_items[0]) if face_items else None

        if is_crisis:
            signal = EmotionSignal(
                state="low",
                emotion_type=current_emotion,
                emotion_score=min(current_score, 30),
                trigger_reason="当前消息包含需要优先安全支持的表达",
                is_crisis=True,
            )
            return EmotionOrchestrationResult(signal=signal, bundle=self._build_crisis_bundle(signal))

        if len(negative_summaries) >= 2:
            score = min([self._summary_score(item) for item in negative_summaries] + [current_score])
            signal = EmotionSignal(
                state="sustained_low",
                emotion_type=self._summary_emotion(negative_summaries[0]) or current_emotion,
                emotion_score=score,
                trigger_reason=f"最近 {len(recent)} 条日记摘要中有 {len(negative_summaries)} 条偏负向",
            )
            return EmotionOrchestrationResult(signal=signal, bundle=self._build_service_bundle(signal))

        if decline is not None and decline >= 15:
            latest_score = self._summary_score(recent[0]) if recent else current_score
            signal = EmotionSignal(
                state="declining",
                emotion_type=self._summary_emotion(recent[0]) if recent else current_emotion,
                emotion_score=latest_score,
                trigger_reason=f"最近情绪评分下降了 {decline} 分",
            )
            return EmotionOrchestrationResult(signal=signal, bundle=self._build_service_bundle(signal))

        if negative_face_count >= FACE_SUSTAINED_LOW_THRESHOLD:
            signal = EmotionSignal(
                state="sustained_low",
                emotion_type=latest_face_emotion or current_emotion,
                emotion_score=min(current_score, 35),
                trigger_reason=f"聊天时表情持续低落（近窗内检测到 {negative_face_count} 次负面表情）",
            )
            return EmotionOrchestrationResult(signal=signal, bundle=self._build_service_bundle(signal))

        if current_negative:
            signal = EmotionSignal(
                state="low",
                emotion_type=current_emotion,
                emotion_score=current_score,
                trigger_reason="当前聊天内容表现出低落或负面情绪",
            )
            return EmotionOrchestrationResult(signal=signal, bundle=self._build_service_bundle(signal))

        return EmotionOrchestrationResult(
            signal=EmotionSignal(
                state="normal",
                emotion_type="中性",
                emotion_score=60,
                trigger_reason="未检测到需要触发服务编排的负面波动",
            )
        )

    def _build_service_bundle(self, signal: EmotionSignal) -> ServiceBundle:
        from app.services.music_library import normalize_mood

        prompt = (
            "一幅安静、柔和、带有治愈感的画面：雨后窗边的微光、温暖的小灯、"
            "逐渐舒展的云层，表达从低落中慢慢恢复的过程"
        )
        auto_white_noise = ServiceAction(
            id="rain_sound",
            title="播放雨声",
            description="先放一点轻柔背景音，帮你把注意力放慢。",
            action="play_white_noise",
            payload={"sound_id": "rain", "volume": "0.45"},
            auto_start=True,
        )
        listen_music = ServiceAction(
            id="listen_music",
            title="听点音乐",
            description="选几首轻柔的音乐，让心情慢慢松下来。",
            action="open_music",
            payload={"mood": normalize_mood(signal.emotion_type)},
        )
        services = [
            auto_white_noise,
            listen_music,
            ServiceAction(
                id="healing_drawing",
                title="生成一幅画",
                description="用柔和画面承接现在的情绪。",
                action="open_drawing",
                payload={"source": "emotion_bundle", "prompt": prompt},
            ),
            ServiceAction(
                id="healing_video",
                title="制作疗愈视频",
                description="先进入绘图流程，生成画面后再做情绪转化视频。",
                action="open_drawing",
                payload={"source": "emotion_bundle", "mode": "healing_video", "prompt": prompt},
            ),
            ServiceAction(
                id="daily_mood_check",
                title="做心情打卡",
                description="用一分钟记录今天的状态。",
                action="open_test",
                payload={"test_id": "daily_mood_v1", "source": "emotion_bundle"},
            ),
            ServiceAction(
                id="adjust_todo",
                title="调整今天待办",
                description="把任务拆小一点，先只留最重要的一件。",
                action="open_todo_create",
                payload={"todo_date": date.today().isoformat(), "source": "emotion_bundle"},
            ),
            ServiceAction(
                id="continue_chat",
                title="继续聊聊",
                description="不用急着解决问题，先把刚才的感受说完。",
                action="continue_chat",
                payload={"source": "emotion_bundle"},
            ),
        ]
        return ServiceBundle(
            bundle_id=f"bundle_{uuid.uuid4().hex[:10]}",
            title="情绪关怀",
            message="最近情绪有些低落，我为你准备了几件小事。",
            trigger_reason=signal.trigger_reason,
            services=services,
            auto_actions=[auto_white_noise],
            is_crisis=False,
        )

    def _build_crisis_bundle(self, signal: EmotionSignal) -> ServiceBundle:
        services = [
            ServiceAction(
                id="continue_chat",
                title="继续说给我听",
                description="我会先陪你把这一刻撑过去。",
                action="continue_chat",
                payload={"source": "emotion_bundle", "priority": "crisis_support"},
            ),
            ServiceAction(
                id="calming_sound",
                title="播放白噪音",
                description="用很低音量的背景声帮你稳住呼吸。",
                action="play_white_noise",
                payload={"sound_id": "white_noise", "volume": "0.35"},
                auto_start=False,
            ),
        ]
        return ServiceBundle(
            bundle_id=f"bundle_{uuid.uuid4().hex[:10]}",
            title="先保证安全",
            message="我注意到你可能正在经历很难熬的时刻。请先联系身边可信任的人，或当地紧急援助服务；我也可以继续陪你说。",
            trigger_reason=signal.trigger_reason,
            services=services,
            auto_actions=[],
            is_crisis=True,
        )

    @staticmethod
    def _message_is_negative(message: str) -> bool:
        lowered = (message or "").lower()
        return any(pattern.lower() in lowered for pattern in NEGATIVE_TEXT_PATTERNS)

    @staticmethod
    def _message_is_crisis(message: str) -> bool:
        lowered = (message or "").lower()
        return any(pattern.lower() in lowered for pattern in CRISIS_TEXT_PATTERNS)

    @staticmethod
    def _infer_message_emotion(message: str) -> str:
        text = message or ""
        if "焦虑" in text or "anxious" in text.lower():
            return "焦虑"
        if "愤怒" in text or "生气" in text or "angry" in text.lower():
            return "愤怒"
        if "孤独" in text or "lonely" in text.lower():
            return "孤独"
        return "低落"

    def _summary_is_negative(self, item: Any) -> bool:
        return self._summary_score(item) <= 45 or self._summary_emotion(item).lower() in NEGATIVE_EMOTIONS

    @staticmethod
    def _summary_score(item: Any) -> int:
        value = getattr(item, "emotion_score", None)
        if value is None and isinstance(item, dict):
            value = item.get("emotion_score")
        try:
            return int(value)
        except (TypeError, ValueError):
            return 50

    @staticmethod
    def _summary_emotion(item: Any) -> str:
        value = getattr(item, "primary_emotion", None)
        if value is None and isinstance(item, dict):
            value = item.get("primary_emotion")
        return str(value or "中性").strip()

    def _recent_decline(self, recent: List[Any]) -> Optional[int]:
        if len(recent) < 2:
            return None
        scores = [self._summary_score(item) for item in recent[:2]]
        if any(score < 1 or score > 100 for score in scores):
            return None
        return scores[1] - scores[0]

    @staticmethod
    def _face_signal_emotion(item: Any) -> str:
        value = getattr(item, "emotion_type", None)
        if value is None and isinstance(item, dict):
            value = item.get("emotion_type")
        return str(value or "").strip().lower()

    def _face_signal_is_negative(self, item: Any) -> bool:
        return self._face_signal_emotion(item) in FACE_NEGATIVE_EMOTIONS
