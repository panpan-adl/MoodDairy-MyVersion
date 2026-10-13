"""
无声视频后期配乐服务

背景：当前账号可用的 Seedance 1.0 系列（pro / pro fast）只能生成无声视频，
账号下支持音画同步的 1.5 Pro 已被官方弃用（deprecated）、2.0 需余额门槛。

本服务在视频任务成功后做一次轻量后期：
    下载无声 mp4
    -> ffmpeg 混入内置疗愈音乐（音量压低、首尾淡入淡出）
    -> 上传 OSS
    -> 返回带音轨的新 URL

设计原则：
- 配乐是“锦上添花”，任何环节失败都必须降级返回原始无声视频 URL，绝不阻断主流程
- 结果按 task_id 缓存（后端强制单进程模式运行），避免重复轮询触发重复混音
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Dict, Optional, Set

import httpx
import imageio_ffmpeg

from app.config import settings
from app.services.oss_service import OSSService

logger = logging.getLogger(__name__)

# 内置疗愈音乐（轻柔钢琴，来源：App res/raw/light_piano.ogg）
_AUDIO_ASSET = Path(__file__).resolve().parent.parent / "assets" / "audio" / "healing.ogg"

# 配乐参数
_MUSIC_VOLUME = 0.55          # 背景音乐音量（避免盖过观感）
_FADE_IN_SECONDS = 1.0        # 开头淡入
_FADE_OUT_SECONDS = 2.0       # 结尾淡出
_FFMPEG_TIMEOUT = 120         # 单次混音超时（秒）
_DOWNLOAD_TIMEOUT = 60        # 视频下载超时（秒）

# task_id -> 已处理好的带音轨 URL
_processed_cache: Dict[str, str] = {}
# 正在处理中的任务，防止并发轮询导致重复混音
_in_flight: Set[str] = set()
_cache_lock = asyncio.Lock()

_oss_service: Optional[OSSService] = None


def _get_oss_service() -> Optional[OSSService]:
    """惰性创建 OSS 服务（单例）；未启用或初始化失败时返回 None。"""
    global _oss_service
    if _oss_service is not None:
        return _oss_service
    if not getattr(settings, "oss_enabled", False):
        return None
    try:
        _oss_service = OSSService(
            endpoint=settings.oss_endpoint,
            bucket_name=settings.oss_bucket,
            access_key_id=settings.oss_access_key_id,
            access_key_secret=settings.oss_access_key_secret,
            public_base_url=settings.oss_public_base_url,
            prefix=settings.oss_prefix,
        )
    except Exception as exc:  # 初始化失败不致命，后续降级返回原 URL
        logger.warning("配乐上传初始化 OSS 失败，将返回无声原片: %s", exc)
        _oss_service = None
    return _oss_service


def _probe_duration_seconds(video_path: Path) -> Optional[float]:
    """用 ffmpeg -i 解析视频时长（imageio-ffmpeg 不附带 ffprobe）。"""
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    proc = subprocess.run(
        [ffmpeg, "-i", str(video_path)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", proc.stderr)
    if not match:
        return None
    hours, minutes, seconds = match.groups()
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def _mix_sync(video_url: str) -> Optional[str]:
    """
    同步执行：下载无声视频 -> ffmpeg 配乐 -> 上传 OSS。

    Returns:
        带音轨的新 URL；任何步骤失败返回 None（调用方降级使用原 URL）。
    """
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    oss = _get_oss_service()
    if oss is None:
        logger.info("OSS 不可用，跳过视频配乐")
        return None
    if not _AUDIO_ASSET.exists():
        logger.warning("配乐素材不存在: %s", _AUDIO_ASSET)
        return None

    with tempfile.TemporaryDirectory(prefix="video_mix_") as tmpdir:
        tmp = Path(tmpdir)
        silent_path = tmp / "silent.mp4"
        mixed_path = tmp / "mixed.mp4"

        # 1. 下载火山返回的无声视频（预签名 URL，24h 有效）
        try:
            with httpx.Client(timeout=_DOWNLOAD_TIMEOUT, follow_redirects=True) as client:
                with client.stream("GET", video_url) as resp:
                    resp.raise_for_status()
                    with open(silent_path, "wb") as f:
                        for chunk in resp.iter_bytes(chunk_size=1 << 16):
                            f.write(chunk)
        except Exception as exc:
            logger.warning("下载无声视频失败，跳过配乐: %s", exc)
            return None

        # 2. 解析时长，构造结尾淡出时间点
        duration = _probe_duration_seconds(silent_path)
        if not duration or duration <= 0:
            logger.warning("无法解析视频时长，跳过配乐")
            return None
        fade_out_start = max(0.0, duration - _FADE_OUT_SECONDS)
        audio_filter = (
            f"volume={_MUSIC_VOLUME},"
            f"afade=t=in:st=0:d={_FADE_IN_SECONDS},"
            f"afade=t=out:st={fade_out_start:.2f}:d={_FADE_OUT_SECONDS}"
        )

        # 3. ffmpeg 混音：视频流直接 copy，音频转 AAC；音乐比视频长，用 -shortest 裁齐
        cmd = [
            ffmpeg, "-y",
            "-i", str(silent_path),
            "-i", str(_AUDIO_ASSET),
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "128k",
            "-af", audio_filter,
            "-shortest",
            str(mixed_path),
        ]
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=_FFMPEG_TIMEOUT,
            )
            if proc.returncode != 0 or not mixed_path.exists():
                logger.warning("ffmpeg 混音失败(returncode=%s): %s",
                               proc.returncode, proc.stderr[-800:])
                return None
        except subprocess.TimeoutExpired:
            logger.warning("ffmpeg 混音超时（>%ss）", _FFMPEG_TIMEOUT)
            return None

        # 4. 上传 OSS
        try:
            result = oss.upload_bytes(
                file_bytes=mixed_path.read_bytes(),
                content_type="video/mp4",
                file_extension=".mp4",
            )
            logger.info("视频配乐完成并上传: %s (%.1fs)", result["url"], duration)
            return result["url"]
        except Exception as exc:
            logger.warning("配乐视频上传 OSS 失败，降级返回无声原片: %s", exc)
            return None


async def add_background_music(task_id: str, video_url: str) -> str:
    """
    给无声视频配乐（对外主入口）。

    - 已处理过：直接返回缓存的新 URL
    - 处理中：等待处理完成 / 立刻返回原 URL（不阻塞本次轮询）
    - 失败：返回原始无声视频 URL

    Returns:
        最终给客户端播放的视频 URL。
    """
    cached = _processed_cache.get(task_id)
    if cached:
        return cached

    async with _cache_lock:
        if task_id in _in_flight:
            # 另一个请求正在混音，本次先返回原 URL，下次轮询拿到成品
            return video_url
        _in_flight.add(task_id)

    try:
        mixed_url = await asyncio.get_running_loop().run_in_executor(
            None, _mix_sync, video_url
        )
        if mixed_url:
            _processed_cache[task_id] = mixed_url
            return mixed_url
        return video_url
    except Exception as exc:  # 兜底：绝不让配乐影响视频可用
        logger.warning("配乐流程异常，降级返回无声原片: %s", exc)
        return video_url
    finally:
        async with _cache_lock:
            _in_flight.discard(task_id)
