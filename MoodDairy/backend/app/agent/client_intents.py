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
        return ("open_white_noise", {})
    if c in ("白噪音", "白噪声"):
        return ("open_white_noise", {})
    if "白噪音" in c and len(c) <= 12:
        return ("open_white_noise", {})

    # Test hub
    if any(
        k in c
        for k in (
            "打开测试中心",
            "测试中心",
            "心理测试",
            "做测试",
            "开始测试",
            "测评",
        )
    ):
        return ("open_test_hub", {})

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
        return ("open_todo_create", {"todo_date": today})

    return None
