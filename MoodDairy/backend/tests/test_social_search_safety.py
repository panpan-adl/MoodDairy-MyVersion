from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from app.services.social_search.client import SocialSearchClient
from app.services.social_search.schemas import Platform, SocialSearchItem, SocialSearchResult
from app.services.social_search.tikhub_provider import SocialSearchError, TikHubProvider
from app.services.social_search.charge_policy import prepare_search_arguments

def settings(rate=20):
    return SimpleNamespace(social_search_rate_limit_per_minute=rate,social_search_cache_ttl_seconds=300)

def provider():
    obj=SimpleNamespace()
    for name in ('xhs','douyin','bilibili','zhihu','kuaishou','weibo'):
        setattr(obj,'search_'+name,AsyncMock(return_value=SocialSearchResult(platform=Platform(name),keyword='冥想',items=[],total=0)))
    return obj

@pytest.mark.asyncio
async def test_douyin_integer_time_and_wrapped_results_are_not_dropped():
    p=TikHubProvider('test-only')
    p._request=AsyncMock(return_value={'code':200,'data':[{'aweme_info':{'aweme_id':'123','desc':'冥想','create_time':1720000000,'statistics':{'digg_count':'1,234'}}}]})
    result=await p.search_douyin('冥想')
    assert result.items[0].url=='https://www.douyin.com/video/123'
    assert result.items[0].publish_time=='1720000000' and result.items[0].like_count==1234

@pytest.mark.asyncio
async def test_bilibili_grouped_results_keep_real_video_urls():
    p=TikHubProvider('test-only')
    p._request=AsyncMock(return_value={'code':200,'data':{'data':{'result':[{'result_type':'video','data':[{'bvid':'BVtest','title':'<em>冥想</em>','pic':'//example.com/a.jpg'}]}]}}})
    result=await p.search_bilibili('冥想')
    assert result.items[0].url=='https://www.bilibili.com/video/BVtest' and result.items[0].title=='冥想'

@pytest.mark.asyncio
async def test_xhs_list_response_does_not_crash():
    p=TikHubProvider('test-only')
    p._request=AsyncMock(return_value={'code':200,'data':[{'note_id':'abc','title':'冥想'}]})
    result=await p.search_xhs('冥想')
    assert result.items[0].note_id=='abc'

@pytest.mark.asyncio
async def test_auto_search_never_calls_other_platforms():
    p=provider(); c=SocialSearchClient(p,settings())
    assert await c.search('冥想')==[]
    p.search_bilibili.assert_awaited_once();p.search_zhihu.assert_awaited_once()
    for name in ('douyin','xhs','kuaishou','weibo'):getattr(p,'search_'+name).assert_not_awaited()

@pytest.mark.asyncio
async def test_failure_is_not_fake_content_or_cached():
    p=provider();p.search_douyin.side_effect=SocialSearchError('offline',code='NETWORK_ERROR')
    c=SocialSearchClient(p,settings())
    for _ in range(2):
        with pytest.raises(SocialSearchError,match='offline'):await c.search('冥想',Platform.DOUYIN)
    assert p.search_douyin.await_count==2

@pytest.mark.asyncio
async def test_successful_empty_result_stays_empty_and_cache_does_not_spend_rate():
    p=provider();c=SocialSearchClient(p,settings(rate=1))
    assert await c.search('冥想',Platform.BILIBILI)==[]
    assert await c.search('冥想',Platform.BILIBILI)==[]
    p.search_bilibili.assert_awaited_once()
    with pytest.raises(SocialSearchError) as exc:await c.search('另一个',Platform.BILIBILI)
    assert exc.value.code=='RATE_LIMITED'

@pytest.mark.parametrize('message,platform,expected',[
    ('搜冥想教程','douyin','auto'),('抖音上搜冥想','douyin','douyin'),
    ('不要在抖音搜','douyin','auto'),('只在B站搜，别用微博','weibo','auto'),
    ('小红书上找教程','小红书','xhs'),('搜教程','unknown','auto'),
])
def test_platform_cannot_be_invented_by_model(message,platform,expected):
    assert prepare_search_arguments({'platform':platform,'query':'冥想','limit':'bad'},message)=={'keyword':'冥想','platform':expected,'limit':8}

@pytest.mark.asyncio
async def test_tool_requires_confirmation_and_scoped_message(monkeypatch):
    from app.agent.tools import execute_tool
    from app.config import settings as config
    from app.services.social_search import key_service
    monkeypatch.setattr(config,'social_search_enabled',True)
    client=SimpleNamespace(search=AsyncMock(return_value=[]))
    fetch=AsyncMock(return_value=(client,None));monkeypatch.setattr(key_service,'get_user_search_client',fetch)
    args={'platform':'douyin','keyword':'冥想'}
    blocked=await execute_tool('search_social_content',args,None,1,'conv',user_message='抖音上搜冥想')
    assert blocked.error=='CHARGE_CONFIRMATION_REQUIRED';fetch.assert_not_awaited()
    await execute_tool('search_social_content',args,None,1,'conv',user_message='搜冥想',charge_confirmed=True)
    assert client.search.call_args.kwargs['platform'] is None
    await execute_tool('search_social_content',args,None,1,'conv',user_message='抖音上搜冥想',charge_confirmed=True)
    assert client.search.call_args.kwargs['platform']==Platform.DOUYIN
    again=await execute_tool('search_social_content',args,None,1,'conv')
    assert again.error=='CHARGE_CONFIRMATION_REQUIRED'

@pytest.mark.asyncio
async def test_key_refresh_distinguishes_outage_from_invalid_key(monkeypatch):
    from app.services.social_search import key_service
    record=SimpleNamespace(balance=2,free_credit=1,account_disabled=False,tikhub_email='test',key_status='1')
    monkeypatch.setattr(key_service,'get_record',AsyncMock(return_value=record))
    monkeypatch.setattr(key_service,'get_plain_key',AsyncMock(return_value='test-only'))
    client=SimpleNamespace(get_balance=AsyncMock(side_effect=SocialSearchError('offline')))
    monkeypatch.setattr(key_service,'get_social_search_client_for_key',lambda _:client)
    status=await key_service.refresh_status(None,1)
    assert status['bound'] and status['stale'] and status['unlocked']
    client.get_balance.side_effect=SocialSearchError('bad',code='INVALID_KEY')
    status=await key_service.refresh_status(None,1)
    assert status['bound'] and not status['unlocked'] and status['error_code']=='INVALID_KEY'

@pytest.mark.asyncio
async def test_empty_account_response_cannot_bind_a_key():
    p=TikHubProvider('test-only');p._request=AsyncMock(return_value={'code':200,'data':{}})
    with pytest.raises(SocialSearchError):await p.get_balance()

@pytest.mark.asyncio
async def test_confirmed_search_emits_cards_and_cancel_never_executes(monkeypatch):
    from app.routers import chat
    from app.agent.tools import ToolExecutionResult
    plan=SimpleNamespace(plan_id='plan1',tool='search_social_content',arguments={'platform':'douyin','keyword':'冥想'},request_message='抖音上搜冥想',context_snapshot='')
    store=SimpleNamespace(get_for_confirmation=AsyncMock(return_value=plan),claim_search_confirmation=AsyncMock(),mark_cancelled=AsyncMock(),mark_executed=AsyncMock())
    monkeypatch.setattr(chat,'get_plan_store',lambda:store)
    execute=AsyncMock(return_value=ToolExecutionResult(True,'search_social_content','read_only','找到内容',{'items':[{'url':'https://www.douyin.com/video/123'}],'keyword':'冥想','platform':'douyin'}))
    monkeypatch.setattr(chat,'execute_tool',execute)
    monkeypatch.setattr(chat,'_generate_tool_followup_reply',AsyncMock(return_value='找到视频'))
    req=chat.ChatRequest(user_id=1,conversation_id='conv',message='',confirmation={'plan_id':'plan1','approved':False})
    events=[event async for event in chat._handle_confirmation_turn(req,None)]
    execute.assert_not_awaited()
    req.confirmation.approved=True
    events=[event async for event in chat._handle_confirmation_turn(req,None)]
    assert any(event['type']=='search_results' and event['items'] for event in events)
    assert execute.call_args.kwargs['charge_confirmed'] is True
    assert execute.call_args.kwargs['user_message']==plan.request_message

@pytest.mark.asyncio
async def test_confirmation_cannot_change_platform_after_preview(monkeypatch):
    from app.routers import chat
    plan=SimpleNamespace(plan_id='p',tool='search_social_content',arguments={'platform':'bilibili','keyword':'冥想'})
    store=SimpleNamespace(get_for_confirmation=AsyncMock(return_value=plan),claim_search_confirmation=AsyncMock())
    monkeypatch.setattr(chat,'get_plan_store',lambda:store)
    execute=AsyncMock();monkeypatch.setattr(chat,'execute_tool',execute)
    req=chat.ChatRequest(user_id=1,conversation_id='c',message='',confirmation={'plan_id':'p','approved':True,'edited_arguments':{'platform':'douyin'}})
    events=[event async for event in chat._handle_confirmation_turn(req,None)]
    assert events[-1]['error_code']=='SEARCH_PLAN_CHANGED'
    execute.assert_not_awaited();store.claim_search_confirmation.assert_not_awaited()

@pytest.mark.asyncio
async def test_database_claim_allows_only_one_purchase_and_rejects_expired():
    from datetime import datetime, timedelta
    from sqlalchemy import create_engine
    from sqlalchemy.dialects.postgresql import JSONB
    from sqlalchemy.ext.compiler import compiles
    from app.agent.plan_store import PlanStore, PlanStoreError
    from app.models.database import ChatAgentPlan
    @compiles(JSONB, 'sqlite')
    def sqlite_json(element, compiler, **kw): return 'JSON'
    engine=create_engine('sqlite://')
    ChatAgentPlan.__table__.create(engine)
    with engine.connect() as connection:
        for id,expired in [(1,False),(2,True)]:
            connection.execute(ChatAgentPlan.__table__.insert().values(id=id,plan_id=str(id),user_id=1,conversation_id='c',tool='search_social_content',mode='read_only',arguments={},status='pending',expires_at=datetime.now()+timedelta(minutes=-1 if expired else 1)))
        connection.commit()
        class DB:
            async def execute(self, stmt): return connection.execute(stmt)
            async def commit(self): connection.commit()
        store=PlanStore();db=DB()
        await store.claim_search_confirmation(db,'1',True)
        with pytest.raises(PlanStoreError):await store.claim_search_confirmation(db,'1',True)
        with pytest.raises(PlanStoreError):await store.claim_search_confirmation(db,'2',True)
    engine.dispose()
