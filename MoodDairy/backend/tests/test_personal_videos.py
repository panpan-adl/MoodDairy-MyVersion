import json
from unittest.mock import AsyncMock
import pytest
from fastapi import HTTPException
from app.services import personal_videos as service
from app.services.social_search.tikhub_provider import TikHubProvider, SocialSearchError
from app.routers.personal_videos import ImportRequest, import_videos

@pytest.fixture(autouse=True)
def isolated_index(tmp_path,monkeypatch):
    monkeypatch.setattr(service,"database_path",lambda:tmp_path/"index.sqlite3")

def video(platform="bilibili",id="BV123",title="同事甩锅时确认责任"):
    return dict(platform=platform,note_id=id,title=title,description="职场沟通",author_name="小李",url="https://www.bilibili.com/video/"+id)

@pytest.mark.parametrize("platform,value,expected", [
    ("douyin", " MS4wtest_123- ", "MS4wtest_123-"),
    ("douyin", "我的主页 https://www.douyin.com/user/MS4wtest?from=share", "MS4wtest"),
    ("bilibili", "123", "123"),
    ("bilibili", "https://space.bilibili.com/42/favlist?fid=123&ftype=create", "123"),
    ("bilibili", "https://space.bilibili.com/42/favlist?fid=%31%32%33", "123"),
])
def test_source_links_are_parsed_locally(platform,value,expected):
    assert service.normalize_source(platform,value)==expected

@pytest.mark.parametrize("platform,value", [
    ("douyin", "https://v.douyin.com/test"),
    ("douyin", "https://www.douyin.com.evil.com/user/MS4wtest"),
    ("douyin", "https://www.douyin.com/video/123"),
    ("bilibili", "https://evil.com/?fid=123"),
    ("bilibili", "https://space.bilibili.com/123/favlist"),
])
def test_invalid_source_links_are_rejected(platform,value):
    with pytest.raises(ValueError): service.normalize_source(platform,value)

@pytest.mark.asyncio
async def test_stuck_page_is_not_saved_or_repeated():
    provider=TikHubProvider("test-only")
    provider._request=AsyncMock(return_value={"code":200,"data":{"status_code":0,"aweme_list":[],"max_cursor":50,"has_more":1}})
    with pytest.raises(SocialSearchError, match="分页未前进"):
        await service.import_page(provider,1,"douyin","MS4wtest","cookie",cursor="50")
    assert service.list_items(1)==[]
    assert provider._request.await_count==1

@pytest.mark.asyncio
async def test_route_normalizes_source_before_forwarding(monkeypatch):
    from app.routers import personal_videos as route
    monkeypatch.setattr(route.key_service,"get_record",AsyncMock(return_value=object()))
    monkeypatch.setattr(route.key_service,"get_plain_key",AsyncMock(return_value="test-only"))
    importer=AsyncMock(return_value={"imported":0})
    monkeypatch.setattr(service,"import_page",importer)
    await import_videos(ImportRequest(platform="bilibili",folder_id="https://space.bilibili.com/42/favlist?fid=123",accept_provider_charge=True),1,None)
    assert importer.call_args.args[-1]=="123"

def test_user_isolation_dedup_and_delete():
    service.save(1,[video(),video()]);service.save(2,[video()])
    assert len(service.list_items(1))==1
    assert service.search(3,"甩锅")==[]
    assert service.search(1,"怎样避免被推卸责任",["甩锅"])[0]["note_id"]=="BV123"
    service.delete_items(1,"bilibili")
    assert service.list_items(1)==[] and len(service.list_items(2))==1

@pytest.mark.asyncio
async def test_douyin_page_and_no_cookie_storage():
    provider=TikHubProvider("test-only")
    provider._request=AsyncMock(return_value={"code":200,"data":{"status_code":0,"aweme_list":[{"aweme_id":"123","desc":"同事甩锅","create_time":123,"author":{"nickname":"作者"}}],"max_cursor":50,"has_more":1}})
    result=await service.import_page(provider,1,"douyin","MS4wtest","cookie-secret")
    assert result["has_more"] and result["next_cursor"]=="50"
    assert service.list_items(1)[0]["record_source"]=="抖音喜欢"
    assert b"cookie-secret" not in service.database_path().read_bytes()

@pytest.mark.asyncio
async def test_bilibili_collection_page():
    provider=TikHubProvider("test-only")
    provider._request=AsyncMock(return_value={"code":200,"data":{"code":0,"data":{"medias":[{"bvid":"BV1","title":"呼吸放松","intro":"冥想教程","upper":{"name":"作者"}}],"has_more":False}}})
    result=await service.import_page(provider,1,"bilibili","",folder_id="123")
    assert result["imported"]==1 and result["source"]=="B站收藏"
    assert provider._request.call_args.kwargs["params"]=={"folder_id":"123","pn":1}

@pytest.mark.asyncio
async def test_upstream_failure_never_fakes_results():
    provider=TikHubProvider("test-only")
    provider._request=AsyncMock(return_value={"code":200,"data":{"status_code":8}})
    with pytest.raises(SocialSearchError):await service.import_page(provider,1,"douyin","MS4wtest","cookie")
    assert service.list_items(1)==[]

@pytest.mark.asyncio
async def test_import_requires_explicit_charge_consent():
    with pytest.raises(HTTPException) as exc:await import_videos(ImportRequest(platform="bilibili",folder_id="123"),1,None)
    assert exc.value.status_code==400


@pytest.mark.asyncio
async def test_routes_require_login():
    from fastapi import FastAPI
    from httpx import AsyncClient, ASGITransport
    from app.routers.personal_videos import router
    app=FastAPI();app.include_router(router)
    async with AsyncClient(transport=ASGITransport(app=app),base_url="http://test") as client:
        assert (await client.get("/personal-videos")).status_code==401
        assert (await client.delete("/personal-videos/douyin")).status_code==401

@pytest.mark.asyncio
async def test_chat_tool_returns_only_personal_records():
    from app.agent.tools import _exec_search_personal_videos
    service.save(1,[video()]);service.save(2,[video(id="BVother")])
    result=await _exec_search_personal_videos({"keyword":"推卸责任","terms":["甩锅"]},None,1,"test")
    assert result.ok
    assert [item["note_id"] for item in result.data["items"]]==["BV123"]
