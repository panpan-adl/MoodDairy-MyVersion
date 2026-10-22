"""Redaction must preserve the meaning of ordinary Chinese conversation."""

import pytest

from app.security.pii import redact_pii, sanitize_for_llm


@pytest.mark.parametrize("message", [
    "我今天不太想说话",
    "我今天不太想说话，只想安静待一会儿。",
    "其实我觉得很难过，但不知道怎么说。",
    "我最近感到有点累。",
    "我想告诉你今天发生的事。",
    "我不太认为这是我的错。",
    "用户表示想一个人待一会儿。",
    "我叫他先休息一会儿。",
])
def test_conversational_chinese_is_not_a_person_name(message):
    assert sanitize_for_llm(message) == message


@pytest.mark.parametrize("message,expected", [
    ("我叫张三。今天不太想说话。", "我叫[PERSON]。今天不太想说话。"),
    ("我的名字是欧阳明", "我的名字是[PERSON]"),
    ("姓名：王小明，今天觉得很累。", "姓名：[PERSON]，今天觉得很累。"),
    ("姓名: 李四\n我想告诉你一件事。", "姓名: [PERSON]\n我想告诉你一件事。"),
])
def test_explicit_names_remain_redacted(message, expected):
    assert redact_pii(message) == expected
    assert sanitize_for_llm(expected) == expected


def test_other_identifiers_remain_redacted_without_changing_feelings():
    message = "我今天不太想说话。电话 13812345678，邮箱 demo@example.com，身份证 110101199001011234。"
    assert sanitize_for_llm(message) == (
        "我今天不太想说话。电话 [PHONE]，邮箱 [EMAIL]，身份证 [ID_CARD]。"
    )


@pytest.mark.asyncio
async def test_chat_message_survives_the_outbound_redaction_pipeline():
    from app.routers.chat import _build_agent_messages

    text = "我今天不太想说话"
    messages = await _build_agent_messages(text, "用户最近感到有点累。", [])
    outbound = [sanitize_for_llm(message["content"]) for message in messages]
    assert text in outbound[-1]
    assert "用户最近感到有点累。" in outbound[-1]
    assert "[PERSON]" not in "".join(outbound)
