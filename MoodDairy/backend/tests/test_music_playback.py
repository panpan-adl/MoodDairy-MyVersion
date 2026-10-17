from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi import FastAPI

from app.routers import music
from app.services import netease_music


def music_app():
    app = FastAPI()
    app.include_router(music.router)
    return app


@pytest.mark.asyncio
async def test_playback_returns_audio_url_not_song_page(monkeypatch):
    resolve = AsyncMock(return_value="https://m801.music.126.net/test.mp3")
    monkeypatch.setattr(netease_music, "resolve_song_audio", resolve)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=music_app()), base_url="http://test") as client:
        response = await client.get("/music/tracks/123/playback")
    assert response.status_code == 200
    assert response.json() == {"url": "https://m801.music.126.net/test.mp3"}
    resolve.assert_awaited_once_with("123")


@pytest.mark.asyncio
@pytest.mark.parametrize("song_id", ["abc", "0", "-1", "123456789012345678901", "１２３"])
async def test_invalid_song_never_calls_upstream(monkeypatch, song_id):
    resolve = AsyncMock()
    monkeypatch.setattr(netease_music, "resolve_song_audio", resolve)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=music_app()), base_url="http://test") as client:
        response = await client.get(f"/music/tracks/{song_id}/playback")
    assert response.status_code == 422
    resolve.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("error", [httpx.ConnectError("offline"), ValueError("unavailable")])
async def test_unavailable_audio_is_a_readable_error(monkeypatch, error):
    monkeypatch.setattr(netease_music, "resolve_song_audio", AsyncMock(side_effect=error))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=music_app()), base_url="http://test") as client:
        response = await client.get("/music/tracks/123/playback")
    assert response.status_code == 503
    assert "无法在应用内播放" in response.json()["detail"]


@pytest.mark.asyncio
@pytest.mark.parametrize("audio_type", ["audio/mpeg", "text/html", "application/json"])
async def test_resolver_rejects_webpages_and_preserves_real_audio(monkeypatch, audio_type):
    requests = []

    def handle(request):
        requests.append(request)
        if "enhance/player/url" in request.url.path:
            assert request.url.params['br'] == '320000'
            return httpx.Response(200, json={"data": [{"url": "http://m801.music.126.net/test.mp3"}]})
        assert request.url.scheme == "https"
        return httpx.Response(200, headers={"Content-Type": audio_type}, content=b"audio")

    original_client = httpx.AsyncClient
    monkeypatch.setattr(netease_music.httpx, "AsyncClient",
        lambda **kwargs: original_client(transport=httpx.MockTransport(handle), **kwargs))
    if audio_type.startswith("audio/"):
        assert await netease_music.resolve_song_audio("123") == "https://m801.music.126.net/test.mp3"
    else:
        with pytest.raises(ValueError):
            await netease_music.resolve_song_audio("123")
    assert len(requests) == 2


@pytest.mark.asyncio
async def test_resolver_can_use_public_redirect_when_metadata_is_missing(monkeypatch):
    def handle(request):
        if "enhance/player/url" in request.url.path:
            return httpx.Response(200, json={"data": [{"url": None}]})
        if request.url.host == "music.163.com":
            return httpx.Response(302, headers={"Location": "https://m801.music.126.net/free.mp3"})
        return httpx.Response(200, headers={"Content-Type": "audio/mpeg"}, content=b"audio")

    original_client = httpx.AsyncClient
    monkeypatch.setattr(netease_music.httpx, "AsyncClient",
        lambda **kwargs: original_client(transport=httpx.MockTransport(handle), **kwargs))
    assert await netease_music.resolve_song_audio("123") == "https://m801.music.126.net/free.mp3"
