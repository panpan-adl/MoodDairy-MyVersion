"""Add and validate background music before a generated video is delivered."""
from __future__ import annotations

import asyncio
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Dict, Optional

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

class AudioMixError(RuntimeError):
    pass


# A shared task prevents simultaneous status polls from exposing the silent source.
_jobs: Dict[str, asyncio.Task] = {}


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
        logger.warning("配乐上传初始化 OSS 失败: %s", exc)
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


def _mix_sync(video_url: str) -> str:
    """
    同步执行：下载无声视频 -> ffmpeg 配乐 -> 上传 OSS。

    Returns:
        带音轨的新 URL；失败抛出明确错误，由状态接口提供重试。
    """
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    oss = _get_oss_service()
    if oss is None:
        logger.info("OSS 不可用，跳过视频配乐")
        raise AudioMixError("配乐上传服务暂不可用")
    if not _AUDIO_ASSET.exists():
        logger.warning("配乐素材不存在: %s", _AUDIO_ASSET)
        raise AudioMixError("背景音乐素材不存在")

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
            raise AudioMixError("原视频下载失败，请重试配乐") from exc

        # 2. 解析时长，构造结尾淡出时间点
        duration = _probe_duration_seconds(silent_path)
        if not duration or duration <= 0:
            logger.warning("无法解析视频时长，跳过配乐")
            raise AudioMixError("无法读取原视频时长")
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
            "-stream_loop", "-1", "-i", str(_AUDIO_ASSET),
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "128k",
            "-af", audio_filter,
            "-t", str(duration),
            "-movflags", "+faststart",
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
                raise AudioMixError("视频配乐合成失败")
        except subprocess.TimeoutExpired:
            logger.warning("ffmpeg 混音超时（>%ss）", _FFMPEG_TIMEOUT)
            raise AudioMixError("视频配乐处理超时")

        # Decode the completed AAC track before publishing it.
        probe = subprocess.run([ffmpeg, "-v", "error", "-i", str(mixed_path),
                                "-map", "0:a:0", "-f", "null", "-"],
                               capture_output=True, timeout=30)
        mixed_duration = _probe_duration_seconds(mixed_path)
        if probe.returncode or not mixed_duration or abs(mixed_duration - duration) > 0.5:
            raise AudioMixError("配乐音轨校验失败")

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
            logger.warning("配乐视频上传 OSS 失败: %s", exc)
            raise AudioMixError("配乐视频上传失败，请重试配乐") from exc


async def _produce(video_url: str) -> str:
    try:
        return await asyncio.to_thread(_mix_sync, video_url)
    except AudioMixError:
        raise
    except Exception as exc:
        raise AudioMixError("背景音乐添加失败，请重试配乐") from exc


def background_music_status(task_id: str, video_url: str, retry: bool = False) -> dict:
    job = _jobs.get(task_id)
    if retry and job is not None and job.done() and (job.cancelled() or job.exception() is not None):
        _jobs.pop(task_id, None)
        job = None
    if job is None:
        job = asyncio.create_task(_produce(video_url))
        # Retrieve failures even if the phone stops polling; state remains available for retry.
        job.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
        _jobs[task_id] = job
    if not job.done():
        return dict(status="processing", video_url=None, audio_status="processing", audio_error=None)
    try:
        return dict(status="succeeded", video_url=job.result(), audio_status="ready", audio_error=None)
    except (AudioMixError, asyncio.CancelledError) as exc:
        message = str(exc) or "背景音乐添加失败，请重试配乐"
        return dict(status="failed", video_url=None, audio_status="failed", audio_error=message,
                    error="视频已生成，但背景音乐添加失败，请重试。")


async def add_background_music(task_id: str, video_url: str) -> str:
    """Await the same verified result; never return the silent source as a fallback."""
    background_music_status(task_id, video_url)
    return await asyncio.shield(_jobs[task_id])
