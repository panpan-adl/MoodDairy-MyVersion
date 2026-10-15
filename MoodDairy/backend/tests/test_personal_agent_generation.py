"""Exercise the real SDK/JSON boundary without external services or private records."""
import json
from types import SimpleNamespace

import httpx
import pytest
from fastapi import HTTPException
from app.models.personal_agent_schemas import PlanDraft, ReviewDraft
from app.services import personal_agent_service as agent


def completion(content, finish_reason='stop'):
    return {'id': 'test', 'object': 'chat.completion', 'created': 0, 'model': 'test',
            'choices': [{'index': 0, 'finish_reason': finish_reason,
                         'message': {'role': 'assistant', 'content': content}}]}


def provider(monkeypatch, handler):
    monkeypatch.setenv('ARK_API_KEY', 'synthetic-test-key')
    monkeypatch.setattr(agent, 'get_ark_base_url', lambda: 'https://provider.invalid/v3')
    monkeypatch.setattr(agent, 'build_async_http_client',
                        lambda timeout: httpx.AsyncClient(timeout=timeout, transport=httpx.MockTransport(handler)))


@pytest.mark.asyncio
async def test_planning_request_allows_response_after_old_24_and_30_second_deadlines(monkeypatch):
    calls = []
    def handle(request):
        calls.append(request)
        # A response that takes 35 seconds must fit both the SDK and transport timeout.
        # This modeled deadline avoids a slow test and catches the original 24-second limit.
        if request.extensions['timeout']['read'] < 35:
            raise httpx.ReadTimeout('simulated slow provider', request=request)
        return httpx.Response(200, json=completion(json.dumps({'suggestion': '分步完成报告', 'tasks': [
            {'title': '完成机器学习实验报告', 'domain': 'study', 'minutes': 60, 'next_step': '整理实验结果'}]})))
    provider(monkeypatch, handle)
    plan = await agent.generate_json('生成计划', {'record': '完成机器学习实验报告'}, PlanDraft)
    assert len(calls) == 1
    assert plan.tasks[0].domain == 'study'
    assert calls[0].extensions['timeout']['read'] < 150  # Android AI client budget.


@pytest.mark.asyncio
async def test_review_uses_same_long_deadline_and_accepts_fenced_json(monkeypatch):
    def handle(request):
        assert request.extensions['timeout']['read'] >= 35
        return httpx.Response(200, json=completion('```json\n{"content":"今天整理了报告提纲。"}\n```'))
    provider(monkeypatch, handle)
    review = await agent.generate_json('复盘', {'reflection': '整理了提纲'}, ReviewDraft)
    assert review.content == '今天整理了报告提纲。'


@pytest.mark.asyncio
@pytest.mark.parametrize('failure,expected', [('timeout', '超时'), ('connection', '无法连接'), ('status', '不可用'), ('format', '格式异常'), ('truncated', '不完整'), ('empty', '不完整')])
async def test_failure_is_specific_does_not_retry_or_leak_content(monkeypatch, caplog, failure, expected):
    calls = []
    def handle(request):
        calls.append(request)
        if failure == 'timeout':
            raise httpx.ReadTimeout('private-provider-message', request=request)
        if failure == 'connection':
            raise httpx.ConnectError('private-provider-message', request=request)
        if failure == 'status':
            return httpx.Response(429, json={'error': {'message': 'private-provider-message', 'type': 'rate_limit'}})
        if failure == 'empty':
            return httpx.Response(200, json={'id': 'test', 'choices': []})
        return httpx.Response(200, json=completion('private-provider-message', 'length' if failure == 'truncated' else 'stop'))
    provider(monkeypatch, handle)
    with pytest.raises(HTTPException) as exc:
        await agent.generate_json('生成计划', {'record': 'private-user-record'}, PlanDraft)
    assert exc.value.status_code == 503 and expected in exc.value.detail
    assert len(calls) == 1
    for private in ('private-provider-message', 'private-user-record', 'synthetic-test-key'):
        assert private not in caplog.text
        assert private not in exc.value.detail
