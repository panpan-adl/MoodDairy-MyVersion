from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
import httpx
import json
import base64
import pytest
from fastapi import FastAPI, HTTPException
from app.services import companion_voice as service
from app.routers.voice import router
from app.security.deps import get_current_user_id


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', [False, True])
async def test_direct_short_asr_does_not_upload_or_poll(monkeypatch, failure):
    decode = Mock(return_value=b'pcm')
    recognize = AsyncMock(return_value='今天去上课。')
    if failure:
        recognize.side_effect = RuntimeError('private provider detail')
    monkeypatch.setattr(service, '_decode_pcm', decode)
    monkeypatch.setattr(service, '_recognize_pcm', recognize)
    if failure:
        with pytest.raises(HTTPException) as error:
            await service.transcribe_companion_audio(b'audio')
        assert error.value.status_code == 503
        assert 'private' not in error.value.detail
    else:
        assert await service.transcribe_companion_audio(b'audio') == '今天去上课。'
    decode.assert_called_once_with(b'audio')
    recognize.assert_awaited_once_with(b'pcm')


@pytest.mark.asyncio
async def test_short_asr_sends_raw_pcm_and_preserves_provider_permission_errors(monkeypatch):
    token = AsyncMock(return_value='test-token')
    monkeypatch.setattr(service, '_recognizer', lambda: SimpleNamespace(_get_access_token=token))
    seen = []
    async def handler(request):
        seen.append(request)
        return httpx.Response(200, json={'err_no': 3302})
    original = httpx.AsyncClient
    monkeypatch.setattr(service.httpx, 'AsyncClient', lambda **kwargs:
        original(transport=httpx.MockTransport(handler), **kwargs))
    with pytest.raises(HTTPException) as error:
        await service._recognize_pcm(b'pcm')
    assert error.value.status_code == 503
    payload = json.loads(seen[0].content)
    assert base64.b64decode(payload['speech']) == b'pcm'
    assert payload['len'] == 3
    assert payload['format'] == 'pcm'
    assert seen[0].url.path == '/server_api'
    assert 'token' not in seen[0].url.params


@pytest.mark.asyncio
async def test_route_requires_auth_and_rejects_empty_or_large_recordings(monkeypatch):
    app = FastAPI()
    app.include_router(router)
    transcribe = AsyncMock(return_value='今天去上课。')
    monkeypatch.setattr(service, 'transcribe_companion_audio', transcribe)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        route = '/voice/companion-transcribe'
        assert (await client.post(route, files={'audio': ('voice.m4a', b'audio')})).status_code in (401, 403)
        app.dependency_overrides[get_current_user_id] = lambda: 1
        for name, data in [('voice.wav', b'audio'), ('voice.m4a', b''), ('voice.m4a', b'x' * (2*1024*1024+1))]:
            assert (await client.post(route, files={'audio': (name, data)})).status_code == 400
        response = await client.post(route, files={'audio': ('voice.m4a', b'audio')})
        assert response.json() == {'text': '今天去上课。'}
        transcribe.assert_awaited_once()


@pytest.mark.asyncio
async def test_pcm_skips_conversion_and_rejects_invalid_sample_alignment(monkeypatch):
    recognize = AsyncMock(return_value='你好')
    decode = Mock(side_effect=AssertionError('PCM must not be transcoded'))
    monkeypatch.setattr(service, '_recognize_pcm', recognize)
    monkeypatch.setattr(service, '_decode_pcm', decode)
    assert await service.transcribe_companion_audio(b'\x01\x00' * 160, 'pcm') == '你好'
    for data in (b'', b'x', bytes(512002)):
        with pytest.raises(HTTPException) as error:
            await service.transcribe_companion_audio(data, 'pcm')
        assert error.value.status_code == 400
    recognize.assert_awaited_once()
    decode.assert_not_called()


@pytest.mark.asyncio
async def test_pcm_route_keeps_authentication_and_fixed_format(monkeypatch):
    app = FastAPI()
    app.include_router(router)
    transcribe = AsyncMock(return_value='你好')
    monkeypatch.setattr(service, 'transcribe_companion_audio', transcribe)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        data = b'\x01\x00' * 160
        assert (await client.post('/voice/companion-transcribe', files={'audio': ('voice.pcm', data)})).status_code in (401, 403)
        app.dependency_overrides[get_current_user_id] = lambda: 1
        response = await client.post('/voice/companion-transcribe', files={'audio': ('voice.pcm', data)})
        assert response.json() == {'text': '你好'}
        transcribe.assert_awaited_once_with(data, audio_format='pcm')
