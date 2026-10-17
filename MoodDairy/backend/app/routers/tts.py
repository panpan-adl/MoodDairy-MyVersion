"""
TTS 语音合成路由

主通道：豆包语音合成大模型 2.0（火山引擎，情感语调自然，支持语音指令）；
兜底通道：edge-tts（微软免费在线神经语音），豆包不可用时自动切换。

接口：GET /tts?text=你好呀
返回：audio/mpeg 音频流（mp3），安卓端直接播放即可。
"""
import base64
import json
import logging
import os
import uuid
from typing import List, Optional

import httpx
from dotenv import load_dotenv
from fastapi import APIRouter, Query
from fastapi.responses import StreamingResponse

logger = logging.getLogger(__name__)
load_dotenv()

router = APIRouter(prefix="/tts", tags=["tts"])

# ── 豆包语音合成大模型 2.0 配置 ────────────────────────────────
# API Key 从 语音模型控制台 > API Key 管理 获取
DOUBAO_TTS_API_KEY = os.getenv("DOUBAO_TTS_API_KEY", "")
# 音色 ID 从 控制台 > 音色库 获取；zh_female_vv_uranus_bigtts = 温柔甜心 vv（2.0 音色）
DOUBAO_TTS_SPEAKER = os.getenv("DOUBAO_TTS_SPEAKER", "zh_female_vv_uranus_bigtts")
DOUBAO_TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

# ── edge-tts 兜底配置 ─────────────────────────────────────────
DEFAULT_VOICE = "zh-CN-XiaoyiNeural"


async def _doubao_tts(text: str, speaker: str) -> Optional[bytes]:
    """调用豆包语音合成大模型 2.0，成功返回 mp3 字节，失败返回 None。"""
    if not DOUBAO_TTS_API_KEY:
        return None
    try:
        audio = bytearray()
        # httpx.stream() 不是异步上下文管理器，async 里必须用 AsyncClient().stream()
        async with httpx.AsyncClient() as client:
            async with client.stream(
                "POST",
                DOUBAO_TTS_URL,
                headers={
                    "X-Api-Key": DOUBAO_TTS_API_KEY,
                    "X-Api-Resource-Id": "seed-tts-2.0",
                    "X-Api-Request-Id": str(uuid.uuid4()),
                    "Content-Type": "application/json",
                },
                json={
                    "req_params": {
                        "text": text,
                        "speaker": speaker,
                        "audio_params": {"format": "mp3", "sample_rate": 24000},
                    }
                },
                timeout=10,
            ) as resp:
                if resp.status_code != 200:
                    body = await resp.aread()
                    logger.warning("Doubao TTS HTTP %s: %s", resp.status_code, body[:200])
                    return None
                # 响应是逐行 JSON（每行一个完整对象，以 \n 分隔），需要缓冲拼接
                buffer = b""
                async for chunk in resp.aiter_bytes():
                    buffer += chunk
                    while b"\n" in buffer:
                        line, buffer = buffer.split(b"\n", 1)
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            obj = json.loads(line)
                        except json.JSONDecodeError:
                            logger.warning("Doubao TTS: 无法解析的响应行 %.80s", line)
                            continue
                        if obj.get("code") == 0 and obj.get("data"):
                            audio.extend(base64.b64decode(obj["data"]))
                        elif obj.get("code") not in (0, 20000000):
                            logger.warning("Doubao TTS 错误: %s", json.dumps(obj, ensure_ascii=False)[:200])
                            return None
                # 最后一行可能没有换行符
                tail = buffer.strip()
                if tail:
                    try:
                        obj = json.loads(tail)
                        if obj.get("code") == 0 and obj.get("data"):
                            audio.extend(base64.b64decode(obj["data"]))
                    except json.JSONDecodeError:
                        pass
        return bytes(audio) if len(audio) > 0 else None
    except Exception as exc:
        logger.warning("Doubao TTS 调用异常: %s", exc)
        return None


@router.get(
    "",
    summary="文字转语音（豆包真人情感语音，edge-tts 兜底）",
    description="传入文字，返回 mp3 音频流。豆包语音合成大模型 2.0 优先，失败自动切 edge-tts。",
)
async def text_to_speech(
    text: str = Query(..., description="要合成的文字，最长 500 字", max_length=500),
    voice: str = Query(None, description="edge-tts 兜底音色（一般不用传）"),
    rate: str = Query("+0%", description="语速（仅 edge-tts 生效）"),
    pitch: str = Query("+5Hz", description="音调（仅 edge-tts 生效）"),
):
    # 1) 优先豆包：情感语调自然，支持中英混读
    mp3 = await _doubao_tts(text, DOUBAO_TTS_SPEAKER)
    if mp3:
        return StreamingResponse(iter([mp3]), media_type="audio/mpeg")

    # 2) 兜底 edge-tts
    logger.info("Doubao TTS 不可用，回退 edge-tts")
    try:
        import edge_tts  # 延迟导入：未安装时不影响其它接口
    except ImportError as exc:  # pragma: no cover
        from fastapi import HTTPException
        raise HTTPException(
            status_code=500,
            detail="后端未安装 edge-tts，请在 backend 虚拟环境执行：pip install edge-tts",
        ) from exc

    enriched_text = text.replace("，", "， ").replace("。", "。 ").replace("？", "？ ").replace("！", "！ ")
    communicate = edge_tts.Communicate(
        text=enriched_text, voice=voice or DEFAULT_VOICE, rate=rate, pitch=pitch,
        connect_timeout=5, receive_timeout=10
    )

    async def audio_stream():
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                yield chunk["data"]

    return StreamingResponse(audio_stream(), media_type="audio/mpeg")
