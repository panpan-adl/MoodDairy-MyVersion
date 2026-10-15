"""Heuristic matcher for explicit in-app navigation commands.

Phase-1 agent turn runs without tools and returns on any text, so the LLM never
reaches tool execution. Short navigation intents are routed here so client_action
tools run deterministically.
"""

from __future__ import annotations

from datetime import date
from typing import Any, Dict, Optional, Tuple

MatchResult = Tuple[str, Dict[str, Any]]


def _compact(s: str) -> str:
    return "".join(s.split())


def match_client_action_intent(message: str) -> Optional[MatchResult]:
    """Return (tool_name, arguments) for clear navigation-only commands, else None."""
    raw = message.strip()
    if not raw:
        return None
    c = _compact(raw)
    if not c:
        return None

    from app.agent.support_intents import match_test_request, search_arguments
    test = match_test_request(raw)
    if test is not None:
        return ("open_test_hub", test)
    search = search_arguments(raw)
    if search:
        return ("search_social_content", search)
    if not any(word in c for word in ("不要", "不想", "不用", "取消")) and any(word in c for word in ("想听歌", "听点音乐", "放松一下", "打开音乐", "播放音乐", "推荐歌曲", "推荐几首")):
        return ("open_relax", {})

    today = date.today().isoformat()
    short = len(c) <= 48
    imperative = c.startswith(
        (
            "打开",
            "请打开",
            "帮我打开",
            "我要打开",
            "去",
            "请帮",
            "帮我",
            "给我",
            "跳转",
            "进入",
        )
    )
    if not (short or imperative):
        return None

    if any(word in c for word in ("不要", "不想", "不用", "取消")) and any(word in c for word in ("白噪音", "音乐", "听歌")):
        return None

    # White noise — specific phrases first
    if any(
        k in c
        for k in (
            "打开白噪音",
            "播放白噪音",
            "听白噪音",
            "想听白噪音",
            "白噪音页面",
        )
    ):
        return ("open_relax", {})
    if c in ("白噪音", "白噪声"):
        return ("open_relax", {})
    if "白噪音" in c and len(c) <= 12:
        return ("open_relax", {})

    # Drawing
    if any(k in c for k in ("打开画画", "去画画", "画画页面", "简笔画", "涂鸦")):
        return ("open_drawing", {})

    # Diary editor — default today
    if any(
        k in c
        for k in (
            "写日记",
            "打开日记",
            "新建日记",
            "日记编辑",
            "打开日记本",
            "日记页面",
        )
    ):
        return ("open_diary_editor", {"diary_date": today})

    if any(word in c for word in ("安排", "规划", "计划")) and any(word in c for word in ("学习", "工作", "今天", "任务")) and any(word in c for word in ("帮", "想", "请", "打开", "制定")):
        return ("open_todo_create", {})

    # Todo create screen
    if any(
        k in c
        for k in (
            "待办界面",
            "待办页面",
            "打开待办",
            "新建待办",
            "待办事项",
            "待办列表",
        )
    ):
        return ("open_todo_create", {})

    return None
