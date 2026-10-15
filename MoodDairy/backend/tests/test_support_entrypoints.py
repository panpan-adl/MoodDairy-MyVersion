"""Refocused support flows: explicit entry cards and confirmed resource discovery."""
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from app.agent.client_intents import match_client_action_intent
from app.agent.support_intents import TESTS, is_psychology_search, resource_label
from app.agent.tools import execute_tool, get_tool_schemas, get_tool_definition


@pytest.mark.parametrize("message,test_id", [
    ("我想测一下我的MBTI", "mbti_simplified_v1"),
    ("想做焦虑测试", "gad7_v1"), ("测一下动物原型", "animal_personality_v1"),
    ("我想做心理测试", None), ("不想测MBTI", False),
])
def test_explicit_test_requests(message, test_id):
    intent = match_client_action_intent(message)
    if test_id is False:
        assert intent is None
    else:
        assert intent[0] == "open_test_hub"
        assert intent[1].get("test_id") == test_id


def test_test_catalog_matches_android_assets():
    import json
    from pathlib import Path
    catalog = Path(__file__).resolve().parents[2] / "app/src/main/assets/tests/index.json"
    ids = {test["test_id"] for test in json.loads(catalog.read_text(encoding="utf-8"))["tests"]}
    assert set(TESTS) == ids


@pytest.mark.asyncio
async def test_test_and_relax_offer_actions_do_not_play_or_navigate():
    result = await execute_tool("open_test_hub", {"test_id": "mbti_simplified_v1"}, None, 1, "c")
    assert result.client_action == {"action": "offer_tests", "payload": {"test_id": "mbti_simplified_v1", "history": "false"}}
    for tool in ("open_relax", "open_white_noise"):
        result = await execute_tool(tool, {}, None, 1, "c")
        assert result.client_action["action"] == "open_relax"
    with pytest.raises(ValueError):
        await execute_tool("open_test_hub", {"test_id": "missing"}, None, 1, "c")


@pytest.mark.parametrize("message,expected", [
    ("推荐几本心理方面的书籍", True), ("帮我找关于焦虑的纪录片", True),
    ("我今天很焦虑，想和你聊聊", False), ("找美食视频", False),
    ("不要搜索心理书籍", False), ("B站有没有正念冥想教程", True),
])
def test_only_requested_psychological_resources_search(message, expected):
    assert is_psychology_search(message) is expected
    intent = match_client_action_intent(message)
    assert bool(intent and intent[0] == "search_social_content") is expected


@pytest.mark.asyncio
async def test_search_shortcut_creates_charge_plan_without_model_or_purchase(monkeypatch):
    from app.routers import chat
    monkeypatch.setattr(chat, "_build_full_context", AsyncMock(return_value=""))
    monkeypatch.setattr(chat, "_fetch_recent_face_signals", AsyncMock(return_value=[]))
    store = SimpleNamespace(create_plan=AsyncMock(return_value=SimpleNamespace(plan_id="p", expires_at=datetime.now()+timedelta(minutes=5))))
    monkeypatch.setattr(chat, "get_plan_store", lambda: store)
    execute = AsyncMock()
    monkeypatch.setattr(chat, "execute_tool", execute)
    request = chat.ChatRequest(user_id=1, conversation_id="c", message="帮我找心理方面的纪录片")
    events = [event async for event in chat._handle_agent_turn(request, None)]
    assert [event["type"] for event in events] == ["tool_plan", "awaiting_confirmation"]
    assert events[0]["arguments"]["platform"] == "auto"
    assert store.create_plan.call_args.kwargs["request_message"] == request.message
    execute.assert_not_awaited()


@pytest.mark.asyncio
async def test_generic_search_cannot_be_purchased(monkeypatch):
    from app.services.social_search import key_service
    fetch = AsyncMock()
    monkeypatch.setattr(key_service, "get_user_search_client", fetch)
    result = await execute_tool("search_social_content", {"keyword": "美食"}, None, 1, "c", user_message="找美食视频", charge_confirmed=True)
    assert result.error == "OUT_OF_SCOPE"
    fetch.assert_not_awaited()


def test_removed_tools_and_conservative_resource_labels():
    assert get_tool_definition("search_personal_videos") is None
    assert "create_todo" not in {tool["function"]["name"] for tool in get_tool_schemas()}
    assert resource_label("心理学书评") == "书评 / 阅读分享"
    assert "解说" in resource_label("心理纪录片")
    assert "完整" not in resource_label("一段情绪科普")
