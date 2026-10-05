"""Music library helpers: normalize emotion labels to music moods."""

from __future__ import annotations

# 情绪标签 -> 音乐心情 映射
_MOOD_MAP = {
    # 中文
    "低落": "healing",
    "悲伤": "healing",
    "难过": "healing",
    "沮丧": "healing",
    "痛苦": "healing",
    "孤独": "healing",
    "焦虑": "calm",
    "烦躁": "calm",
    "疲惫": "calm",
    "愤怒": "release",
    "开心": "happy",
    "快乐": "happy",
    "兴奋": "energetic",
    "平静": "calm",
    "中性": "calm",
    # 英文
    "sad": "healing",
    "depressed": "healing",
    "lonely": "healing",
    "anxious": "calm",
    "tired": "calm",
    "angry": "release",
    "happy": "happy",
    "joy": "happy",
    "excited": "energetic",
    "calm": "calm",
    "neutral": "calm",
    "fear": "calm",
    "disgust": "release",
}

DEFAULT_MOOD = "calm"


def normalize_mood(emotion: str | None) -> str:
    """Map an emotion label (CN/EN) to a music mood key.

    Returns one of: healing / calm / release / happy / energetic.
    Unknown or empty labels fall back to ``DEFAULT_MOOD``.
    """
    if not emotion:
        return DEFAULT_MOOD
    return _MOOD_MAP.get(str(emotion).strip().lower(), DEFAULT_MOOD) or _MOOD_MAP.get(
        str(emotion).strip(), DEFAULT_MOOD
    )
