"""Direct short-utterance ASR without OSS uploads, polling, or diary writes."""
import asyncio
import base64
import logging
import os
import subprocess
import time
from functools import lru_cache
import httpx
import imageio_ffmpeg
from fastapi import HTTPException
from app.services.external_api_client import BaiduASRClient

logger = logging.getLogger(__name__)

@lru_cache(maxsize=1)
def _recognizer():
    key, secret = os.getenv('BAIDU_API_KEY'), os.getenv('BAIDU_SECRET_KEY')
    if not key or not secret:
        raise HTTPException(503, '语音识别尚未配置')
    return BaiduASRClient(key, secret)

def _decode_pcm(data: bytes) -> bytes:
    # Preserve existing M4A clients; decode locally to 16-kHz mono PCM.
    result = subprocess.run([
        imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-i', 'pipe:0',
        '-t', '16', '-ac', '1', '-ar', '16000', '-f', 's16le', 'pipe:1'
    ], input=data, capture_output=True, timeout=8, check=True,
        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if not result.stdout:
        raise ValueError('Empty decoded audio')
    return result.stdout

async def _recognize_pcm(pcm: bytes) -> str:
    recognizer = _recognizer()  # Reuse the access token across utterances.
    token = await recognizer._get_access_token()
    async with httpx.AsyncClient(timeout=httpx.Timeout(20, connect=5)) as client:
        response = await client.post('https://vop.baidu.com/server_api',
            # Keep credentials out of URLs recorded by HTTP access loggers.
            json={'token': token, 'cuid': 'mooddiary-companion', 'dev_pid': 1537,
                  'format': 'pcm', 'rate': 16000, 'channel': 1, 'len': len(pcm),
                  'speech': base64.b64encode(pcm).decode('ascii')})
        response.raise_for_status()
        result = response.json()
    code = result.get('err_no')
    if code == 3301:
        return ''
    if code != 0:
        logger.warning('Short ASR provider error: code=%s', code)
        if code in (3302, 3304, 3305, 3310, 3312):
            raise HTTPException(503, '短语音识别权限或额度不可用，请检查服务配置')
        raise HTTPException(503, '语音识别暂不可用，请稍后再试')
    return ''.join(result.get('result') or []).strip()

async def transcribe_companion_audio(data: bytes, audio_format: str = 'm4a') -> str:
    started = time.monotonic()
    try:
        async with asyncio.timeout(30):
            if audio_format == 'pcm':
                if not data or len(data) % 2 or len(data) > 16000 * 2 * 16:
                    raise HTTPException(400, 'PCM 录音规格无效或超过长度限制')
                pcm = data
            elif audio_format == 'm4a':
                pcm = await asyncio.to_thread(_decode_pcm, data)
            else:
                raise HTTPException(400, '录音格式不支持')
            decoded = time.monotonic()
            text = await _recognize_pcm(pcm)
        logger.info('Companion ASR timing: audio_ms=%d decode_ms=%d total_ms=%d',
            len(pcm) * 1000 // 32000, int((decoded-started)*1000),
            int((time.monotonic()-started)*1000))
        return text
    except HTTPException:
        raise
    except (TimeoutError, httpx.TimeoutException):
        raise HTTPException(504, '语音识别超时，请再试一次') from None
    except Exception as error:
        logger.warning('Companion ASR failed: error_type=%s', type(error).__name__)
        raise HTTPException(503, '语音识别暂不可用，请稍后再试') from None
