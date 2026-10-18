import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.agent import tools
from app.config import settings
from app.routers import chat
from app.services.social_search import client as searches, key_service
from app.services.social_search.schemas import Platform, SocialSearchItem, SocialSearchResult
from app.services.social_search.tikhub_provider import SocialSearchError


def provider():
    result = SimpleNamespace()
    for platform in Platform:
        setattr(result, 'search_' + platform.value, AsyncMock(return_value=SocialSearchResult(
            platform=platform, keyword='心理书籍', items=[], total=0)))
    return result


def search_client(p):
    return searches.SocialSearchClient(p, SimpleNamespace(
        social_search_rate_limit_per_minute=20, social_search_cache_ttl_seconds=300))


def item(platform):
    return SocialSearchItem(platform=platform, note_id='1', title='心理书籍', url='https://example.com/book')


@pytest.mark.asyncio
async def test_auto_search_starts_both_platforms_without_serial_wait(monkeypatch):
    monkeypatch.setattr(searches, 'search_platform_timeout_seconds', lambda: .2)
    second_started = asyncio.Event()
    p = provider()
    async def first(*args):
        await second_started.wait()
        return SocialSearchResult(platform=Platform.BILIBILI, keyword='心理书籍', items=[item(Platform.BILIBILI)], total=1)
    async def second(*args):
        second_started.set()
        return SocialSearchResult(platform=Platform.ZHIHU, keyword='心理书籍', items=[item(Platform.ZHIHU)], total=1)
    p.search_bilibili.side_effect = first
    p.search_zhihu.side_effect = second
    results = await search_client(p).search('心理书籍')
    assert {r.platform for r in results} == {Platform.BILIBILI, Platform.ZHIHU}
    p.search_bilibili.assert_awaited_once()
    p.search_zhihu.assert_awaited_once()


@pytest.mark.asyncio
async def test_slow_platform_is_cancelled_and_fast_results_survive(monkeypatch):
    monkeypatch.setattr(searches, 'search_platform_timeout_seconds', lambda: .01)
    cancelled = asyncio.Event()
    p = provider()
    async def slow(*args):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()
    p.search_bilibili.side_effect = slow
    p.search_zhihu.return_value = SocialSearchResult(platform=Platform.ZHIHU,
        keyword='心理书籍', items=[item(Platform.ZHIHU)], total=1)
    c = search_client(p)
    results = await c.search('心理书籍')
    assert results[0].platform == Platform.ZHIHU and cancelled.is_set()
    # A partial result must not hide a failed platform in the complete-result cache.
    await c.search('心理书籍')
    assert p.search_bilibili.await_count == 2


@pytest.mark.asyncio
async def test_all_platform_timeouts_report_search_specific_error(monkeypatch):
    monkeypatch.setattr(searches, 'search_platform_timeout_seconds', lambda: .01)
    p = provider()
    async def slow(*args): await asyncio.Event().wait()
    p.search_bilibili.side_effect = slow
    p.search_zhihu.side_effect = slow
    with pytest.raises(SocialSearchError) as error:
        await search_client(p).search('心理书籍')
    assert error.value.code == 'SEARCH_TIMEOUT'


def test_outer_search_deadline_covers_provider_budget_without_affecting_other_tools(monkeypatch):
    monkeypatch.setattr(settings, 'tikhub_timeout_seconds', 45)
    assert chat.tool_execution_timeout_seconds('search_social_content') == 55
    assert chat.tool_execution_timeout_seconds('create_diary') == chat.TOOL_EXECUTION_TIMEOUT_SECONDS
    monkeypatch.setattr(settings, 'tikhub_timeout_seconds', 999)
    assert chat.tool_execution_timeout_seconds('search_social_content') == 55


@pytest.mark.asyncio
async def test_empty_result_does_not_trigger_unconfirmed_keyword_variants(monkeypatch):
    monkeypatch.setattr(settings, 'social_search_enabled', True)
    c = SimpleNamespace(search=AsyncMock(return_value=[]))
    monkeypatch.setattr(key_service, 'get_user_search_client', AsyncMock(return_value=(c, None)))
    result = await tools.execute_tool('search_social_content', {'keyword': '关于心理方面的书籍', 'platform': 'auto'},
        AsyncMock(), 1, 'test', user_message='搜一本关于心理方面的书籍', charge_confirmed=True)
    assert result.ok and result.data['items'] == []
    c.search.assert_awaited_once_with(keyword='关于心理方面的书籍', platform=None, limit=8)
