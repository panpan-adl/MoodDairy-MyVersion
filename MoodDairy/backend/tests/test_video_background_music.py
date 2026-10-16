import asyncio
import re
import subprocess
from unittest.mock import MagicMock, AsyncMock

import httpx
import imageio_ffmpeg
import pytest

from app.drawing import video_audio_mixer as mixer
from app.drawing.video_service import VideoService


@pytest.mark.asyncio
async def test_concurrent_polls_share_music_job_and_never_expose_silent_source(monkeypatch):
    monkeypatch.setattr(mixer, '_jobs', {})
    release = asyncio.Event()
    calls = []

    async def produce(source):
        calls.append(source)
        await release.wait()
        return 'https://example.com/musical.mp4'

    monkeypatch.setattr(mixer, '_produce', produce)
    first = mixer.background_music_status('task1', 'https://example.com/silent.mp4')
    second = mixer.background_music_status('task1', 'https://example.com/silent.mp4')
    assert first == second and first['status'] == 'processing' and first['video_url'] is None
    await asyncio.sleep(0)
    assert len(calls) == 1
    release.set()
    await mixer.add_background_music('task1', 'https://example.com/silent.mp4')
    ready = mixer.background_music_status('task1', 'https://example.com/silent.mp4')
    assert ready['audio_status'] == 'ready' and ready['video_url'].endswith('musical.mp4')


@pytest.mark.asyncio
async def test_audio_failure_requires_explicit_retry_and_does_not_regenerate_video(monkeypatch):
    monkeypatch.setattr(mixer, '_jobs', {})
    calls = []

    async def produce(source):
        calls.append(source)
        if len(calls) == 1:
            raise mixer.AudioMixError('测试上传失败')
        return 'https://example.com/repaired.mp4'

    monkeypatch.setattr(mixer, '_produce', produce)
    provider = MagicMock()
    provider.get_video_task_status = AsyncMock(return_value=dict(task_id='task2', status='succeeded', video_url='https://example.com/silent.mp4'))
    service = VideoService(provider)
    assert (await service.get_video_status('task2'))['status'] == 'processing'
    await asyncio.sleep(0)
    failed = await service.get_video_status('task2')
    assert failed['audio_status'] == 'failed' and failed['video_url'] is None
    assert (await service.get_video_status('task2')) == failed
    assert len(calls) == 1
    await service.get_video_status('task2', retry_audio=True)
    await asyncio.sleep(0)
    assert (await service.get_video_status('task2'))['audio_status'] == 'ready'
    assert len(calls) == 2
    provider.create_video_task.assert_not_called()


def test_real_ffmpeg_output_has_audible_music_and_keeps_video_duration(tmp_path, monkeypatch):
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    source = tmp_path / 'silent.mp4'
    subprocess.run([ffmpeg, '-y', '-f', 'lavfi', '-i', 'color=c=lightblue:s=128x128:r=12',
                    '-t', '3', '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(source)],
                   check=True, capture_output=True, timeout=30)
    response = MagicMock()
    response.iter_bytes.return_value = [source.read_bytes()]
    stream = MagicMock()
    stream.__enter__.return_value = response
    client = MagicMock()
    client.__enter__.return_value.stream.return_value = stream
    monkeypatch.setattr(mixer.httpx, 'Client', lambda **kwargs: client)
    saved = tmp_path / 'with-music.mp4'
    oss = MagicMock()

    def upload(**kwargs):
        saved.write_bytes(kwargs['file_bytes'])
        return {'url': 'https://example.com/music.mp4'}

    oss.upload_bytes.side_effect = upload
    monkeypatch.setattr(mixer, '_get_oss_service', lambda: oss)
    assert mixer._mix_sync('https://example.com/silent.mp4').endswith('/music.mp4')
    assert abs(mixer._probe_duration_seconds(saved) - 3) < 0.5
    probe = subprocess.run([ffmpeg, '-i', str(saved), '-map', '0:a:0', '-af', 'volumedetect',
                            '-f', 'null', '-'], capture_output=True, text=True, timeout=30)
    assert probe.returncode == 0 and 'Audio: aac' in probe.stderr
    volume = re.search(r'mean_volume:\s*([-\d.]+) dB', probe.stderr)
    assert volume and float(volume.group(1)) > -65


def test_missing_music_and_storage_fail_explicitly(tmp_path, monkeypatch):
    monkeypatch.setattr(mixer, '_get_oss_service', lambda: None)
    with pytest.raises(mixer.AudioMixError, match='上传服务'):
        mixer._mix_sync('https://example.com/silent.mp4')
    monkeypatch.setattr(mixer, '_get_oss_service', lambda: MagicMock())
    monkeypatch.setattr(mixer, '_AUDIO_ASSET', tmp_path / 'missing.ogg')
    with pytest.raises(mixer.AudioMixError, match='素材'):
        mixer._mix_sync('https://example.com/silent.mp4')


@pytest.mark.asyncio
async def test_status_alias_and_retry_route_keep_audio_fields(monkeypatch):
    from fastapi import FastAPI
    from importlib import import_module
    routes = import_module("app.drawing.drawing_router")
    service = MagicMock()
    service.get_video_status = AsyncMock(return_value=dict(task_id='qa', status='processing', video_url=None, audio_status='processing', audio_error=None))
    monkeypatch.setattr(routes, 'VideoService', lambda: service)
    app = FastAPI()
    app.include_router(routes.router)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as c:
        for path in ['/drawing/video/status/qa', '/drawing/video-status/qa']:
            result = await c.get(path)
            assert result.json()['audio_status'] == 'processing'
        result = await c.post('/drawing/video/audio/qa/retry')
        assert result.status_code == 200 and result.json()['video_url'] is None
    service.get_video_status.assert_awaited_with('qa', retry_audio=True)
