import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from app.routers import chat
from app.agent import tools
from app.services.social_search.charge_policy import companion_search_arguments, direct_companion_search, search_charge_confirmed

@pytest.mark.parametrize('text', ['不要搜索抖音', '帮我查日记', '今天天气很好'])
def test_non_search_does_not_use_direct_api(text):
    assert companion_search_arguments(text) is None

def test_explicit_platform_and_default_are_preserved():
    assert companion_search_arguments('搜索抖音上的花卉教程')['platform'] == 'douyin'
    assert companion_search_arguments('搜一下操作系统课程')['platform'] == 'auto'

@pytest.mark.asyncio
@pytest.mark.parametrize('message', ['搜一下操作系统课程', '推荐一本心理书'])
async def test_companion_search_skips_plan_and_model(monkeypatch, message):
    items = [{'title': '课程', 'url': 'https://example.com/course', 'platform': 'bilibili'}]
    execute = AsyncMock(return_value=SimpleNamespace(ok=True, data={'items': items}))
    monkeypatch.setattr(chat, 'execute_tool', execute)
    plans = AsyncMock()
    monkeypatch.setattr(chat, 'get_plan_store', lambda: plans)
    model = AsyncMock(side_effect=AssertionError('must not call model'))
    monkeypatch.setattr(chat, '_build_full_context', model)
    request = chat.ChatRequest(user_id=1, conversation_id='test', message=message, companion_search_direct=True)
    events = [e async for e in chat._handle_agent_turn(request, AsyncMock())]
    assert [e['type'] for e in events] == ['search_results', 'done']
    assert events[0]['items'] == items and events[0]['locked'] is False
    assert execute.call_args.kwargs['charge_confirmed'] is True
    assert execute.call_args.kwargs['companion_search'] is True
    plans.create_plan.assert_not_called()
    model.assert_not_called()

@pytest.mark.asyncio
@pytest.mark.parametrize('code', ['NO_KEY', 'NO_BALANCE', 'INVALID_KEY', 'SEARCH_ERROR'])
async def test_errors_do_not_expose_billing_or_account_messages(monkeypatch, code):
    execute = AsyncMock(return_value=SimpleNamespace(ok=False,
        summary='付费 充值 余额 $0.01', data={'error_code': code, 'items': []}))
    monkeypatch.setattr(chat, 'execute_tool', execute)
    request = chat.ChatRequest(user_id=1, conversation_id='test', message='搜索小红书冥想教程', companion_search_direct=True)
    events = [e async for e in chat._handle_agent_turn(request, AsyncMock())]
    payload = json.dumps(events, ensure_ascii=False)
    assert not any(word in payload for word in ['付费', '充值', '余额', '$0.01', code])
    assert events[0]['locked'] is False
    assert events[-1]['reply'] == '搜索暂时不可用，请稍后再试。'

@pytest.mark.asyncio
async def test_tool_direct_context_is_scoped_to_this_search(monkeypatch):
    async def executor(*args):
        assert direct_companion_search.get() and search_charge_confirmed.get()
        return 'result'
    monkeypatch.setattr(tools, 'get_tool_definition', lambda name: SimpleNamespace(executor=executor))
    assert await tools.execute_tool('search_social_content', {}, AsyncMock(), 1, 'test',
        user_message='搜索课程', charge_confirmed=True, companion_search=True) == 'result'
    assert not direct_companion_search.get() and not search_charge_confirmed.get()

def test_normal_chat_keeps_direct_search_disabled_by_default():
    assert chat.ChatRequest(user_id=1, conversation_id='test', message='搜索心理书').companion_search_direct is False

@pytest.mark.asyncio
async def test_direct_search_reaches_configured_user_api(monkeypatch):
    from app.config import settings
    from app.services.social_search import key_service
    from app.services.social_search.schemas import SocialSearchItem, Platform
    monkeypatch.setattr(settings, 'social_search_enabled', True)
    item = SocialSearchItem(platform=Platform.BILIBILI, note_id='1', title='操作系统课程', url='https://example.com/1')
    client = SimpleNamespace(search=AsyncMock(return_value=[item]))
    lookup = AsyncMock(return_value=(client, None))
    monkeypatch.setattr(key_service, 'get_user_search_client', lookup)
    result = await tools.execute_tool('search_social_content', {'keyword': '操作系统', 'platform': 'auto'},
        AsyncMock(), 1, 'test', user_message='搜一下操作系统课程', charge_confirmed=True, companion_search=True)
    assert result.ok and result.data['items'][0]['title'] == item.title
    lookup.assert_awaited_once()
    client.search.assert_awaited_once_with(keyword='操作系统', platform=None, limit=8)

@pytest.mark.asyncio
async def test_default_request_still_requires_confirmation(monkeypatch):
    from app.services.social_search import key_service
    lookup = AsyncMock()
    monkeypatch.setattr(key_service, 'get_user_search_client', lookup)
    result = await tools.execute_tool('search_social_content', {'keyword': '心理'}, AsyncMock(), 1, 'test',
        user_message='搜索心理书')
    assert not result.ok and result.error == 'CHARGE_CONFIRMATION_REQUIRED'
    lookup.assert_not_called()
