from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi import FastAPI

from app.routers import music


def application():
    app = FastAPI()
    app.include_router(music.router)
    return app


@pytest.mark.asyncio
@pytest.mark.parametrize('path', ['/music/netease/recommendations', '/music/recommendations'])
async def test_recommendation_paths_return_client_compatible_real_song_fields(monkeypatch, path):
    search = AsyncMock(return_value=[{
        'note_id': '1364292313', 'title': 'Towards the Light', 'author_name': 'Jacoo',
        'description': '', 'url': 'https://music.163.com/song?id=1364292313', 'cover_url': None,
    }])
    monkeypatch.setattr(music.netease_music, 'search_songs', search)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application()), base_url='http://test') as client:
        response = await client.get(path, params={'mood': 'healing', 'limit': 5})
    assert response.status_code == 200
    body = response.json()
    assert body['mood'] == 'healing'
    assert body['source'] == 'netease'
    track = body['tracks'][0]
    assert track['source'] == 'netease'
    assert track['jump_url'] == 'https://music.163.com/song?id=1364292313'
    assert track['artist'] == 'Jacoo'
    assert track['mood_tags'] == ['healing']
    search.assert_awaited_once_with('治愈 纯音乐', 5)


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['empty', 'network'])
async def test_upstream_failure_returns_readable_message_instead_of_fake_songs(monkeypatch, failure):
    search = AsyncMock(return_value=[]) if failure == 'empty' else AsyncMock(side_effect=httpx.ConnectError('offline'))
    monkeypatch.setattr(music.netease_music, 'search_songs', search)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application()), base_url='http://test') as client:
        response = await client.get('/music/netease/recommendations')
    assert response.status_code == 503
    assert '网易云' in response.json()['detail']


@pytest.mark.asyncio
async def test_invalid_limit_is_rejected_without_calling_provider(monkeypatch):
    search = AsyncMock()
    monkeypatch.setattr(music.netease_music, 'search_songs', search)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application()), base_url='http://test') as client:
        response = await client.get('/music/netease/recommendations?limit=999')
    assert response.status_code == 422
    search.assert_not_awaited()
