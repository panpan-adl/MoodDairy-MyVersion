"""Volcengine client helpers for image and video generation."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import Any, Dict, Optional

from openai import OpenAI

try:
    from volcenginesdkarkruntime import Ark
except ImportError as exc:  # pragma: no cover - depends on local environment
    Ark = None
    _ARK_IMPORT_ERROR = exc
else:
    _ARK_IMPORT_ERROR = None

from app.utils.ark_runtime import build_http_client, get_ark_base_url, should_trust_env_proxy

logger = logging.getLogger(__name__)


class VolcengineAPIError(Exception):
    """Raised when Volcengine requests fail."""


class VolcengineClient:
    """Thin wrapper around Ark/OpenAI-compatible generation APIs."""

    IMAGE_MODEL = "doubao-seedream-4-5-251128"
    VIDEO_MODEL = "doubao-seedance-1-5-pro-251215"

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key or os.environ.get("ARK_API_KEY")
        if not self.api_key:
            raise VolcengineAPIError("Missing ARK_API_KEY in environment or constructor")

        self.base_url = get_ark_base_url()
        logger.info(
            "Ark client config: base_url=%s trust_env_proxy=%s",
            self.base_url,
            should_trust_env_proxy(),
        )

        self.openai_client = OpenAI(
            base_url=self.base_url,
            api_key=self.api_key,
            http_client=build_http_client(timeout=90.0),
        )
        self.ark_client = (
            Ark(
                base_url=self.base_url,
                api_key=self.api_key,
                http_client=build_http_client(timeout=120.0),
            )
            if Ark is not None
            else None
        )

        if self.ark_client is None:
            logger.warning(
                "volcenginesdkarkruntime is not installed; Ark-only features such as video generation "
                "are disabled, but image generation can still use the OpenAI-compatible API."
            )

    def _require_ark_client(self) -> Any:
        if self.ark_client is None:
            raise VolcengineAPIError(
                "volcenginesdkarkruntime is not installed. Install it separately to enable Ark video features."
            ) from _ARK_IMPORT_ERROR
        return self.ark_client

    async def text_to_image(
        self,
        prompt: str,
        size: str = "2048x2048",
        watermark: bool = False,
    ) -> str:
        logger.info("text_to_image request: size=%s prompt=%s", size, prompt[:100])
        try:
            loop = asyncio.get_running_loop()
            response = await loop.run_in_executor(
                None,
                lambda: self.openai_client.images.generate(
                    model=self.IMAGE_MODEL,
                    prompt=prompt,
                    size=size,
                    response_format="url",
                    extra_body={"watermark": watermark},
                ),
            )
            return response.data[0].url
        except Exception as exc:
            logger.error("text_to_image failed: %s", exc)
            raise VolcengineAPIError(f"text_to_image failed: {exc}") from exc

    async def image_to_image(
        self,
        image_url: str,
        prompt: str,
        size: str = "2048x2048",
        watermark: bool = False,
    ) -> str:
        logger.info("image_to_image request: image=%s prompt=%s", image_url, prompt[:100])
        try:
            loop = asyncio.get_running_loop()
            response = await loop.run_in_executor(
                None,
                lambda: self.openai_client.images.generate(
                    model=self.IMAGE_MODEL,
                    prompt=prompt,
                    size=size,
                    response_format="url",
                    extra_body={"image": image_url, "watermark": watermark},
                ),
            )
            return response.data[0].url
        except Exception as exc:
            logger.error("image_to_image failed: %s", exc)
            raise VolcengineAPIError(f"image_to_image failed: {exc}") from exc

    def _create_video_task_sync(
        self,
        image_url: str,
        prompt: str,
        duration: int = 5,
        camera_fixed: bool = False,
        watermark: bool = True,
    ) -> str:
        params = (
            f"--duration {duration} "
            f"--camerafixed {str(camera_fixed).lower()} "
            f"--watermark {str(watermark).lower()}"
        )
        full_prompt = f"{prompt} {params}"
        content = [
            {"type": "text", "text": full_prompt},
            {"type": "image_url", "image_url": {"url": image_url}},
        ]
        ark_client = self._require_ark_client()
        result = ark_client.content_generation.tasks.create(model=self.VIDEO_MODEL, content=content)
        return result.id

    async def create_video_task(
        self,
        image_url: str,
        prompt: str,
        duration: int = 5,
        camera_fixed: bool = False,
        watermark: bool = True,
    ) -> str:
        logger.info("create_video_task request: image=%s prompt=%s", image_url, prompt[:50])
        try:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                lambda: self._create_video_task_sync(image_url, prompt, duration, camera_fixed, watermark),
            )
        except Exception as exc:
            logger.error("create_video_task failed: %s", exc)
            raise VolcengineAPIError(f"create_video_task failed: {exc}") from exc

    def _get_video_task_status_sync(self, task_id: str) -> Dict[str, Any]:
        ark_client = self._require_ark_client()
        result = ark_client.content_generation.tasks.get(task_id=task_id)
        status_info: Dict[str, Any] = {
            "task_id": task_id,
            "status": result.status,
            "video_url": None,
            "error": None,
        }

        logger.info("_get_video_task_status_sync: task_id=%s status=%s result_type=%s",
                     task_id, result.status, type(result).__name__)

        if result.status == "succeeded":
            # Extract video URL from result.content — handle different API response structures
            video_url = self._extract_video_url_from_result(result)
            if video_url:
                status_info["video_url"] = video_url
                logger.info("Video URL extracted: %s", video_url)
            else:
                # API reports success but we cannot read a playable URL — stop polling with a clear error
                status_info["status"] = "failed"
                status_info["error"] = "任务已完成但未能解析视频地址，请稍后重试"
                logger.error(
                    "Video task succeeded but no video_url found; raw=%s",
                    self._safe_task_debug_repr(result),
                )
        elif result.status == "failed":
            status_info["error"] = str(getattr(result, "error", "unknown error"))

        return status_info

    @staticmethod
    def _is_plausible_video_url(url: str) -> bool:
        u = url.strip().lower()
        if not u.startswith("http"):
            return False
        return (
            ".mp4" in u
            or ".webm" in u
            or ".mov" in u
            or "video" in u
            or "tos-" in u
            or "volces.com" in u
        )

    def _coerce_url_string(self, value: Any) -> Optional[str]:
        if value is None:
            return None
        if isinstance(value, str):
            u = value.strip()
            return u if u.startswith("http") else None
        url = getattr(value, "url", None)
        if isinstance(url, str) and url.startswith("http"):
            return url
        return None

    def _extract_video_url_from_jsonish(self, obj: Any) -> Optional[str]:
        """Depth-first search for a video URL in dict/list/primitive structures."""
        if obj is None:
            return None
        if isinstance(obj, str):
            u = obj.strip()
            if self._is_plausible_video_url(u):
                return u
            return None
        if isinstance(obj, dict):
            for key in ("video_url", "file_url", "url", "last_frame_url"):
                if key not in obj:
                    continue
                val = obj[key]
                if isinstance(val, str):
                    u = val.strip()
                    if u.startswith("http") and (key == "video_url" or self._is_plausible_video_url(u)):
                        return u
                found = self._extract_video_url_from_jsonish(val)
                if found and (key == "video_url" or self._is_plausible_video_url(found)):
                    return found
            for v in obj.values():
                found = self._extract_video_url_from_jsonish(v)
                if found:
                    return found
            return None
        if isinstance(obj, (list, tuple)):
            for it in obj:
                found = self._extract_video_url_from_jsonish(it)
                if found:
                    return found
            return None
        return None

    def _safe_task_debug_repr(self, result) -> str:
        try:
            if hasattr(result, "model_dump"):
                return str(result.model_dump(mode="json"))
        except Exception as exc:  # pragma: no cover - defensive
            return f"<model_dump failed: {exc}>"
        return repr(result)

    def _extract_video_url_from_result(self, result) -> Optional[str]:
        """
        Extract video URL from the API result object.
        Ark SDK models `content` as a single object with video_url/file_url; older code
        iterated `for item in content` which breaks when `content` is a str or model
        (e.g. iterating per-character strings).
        """
        # Direct task-level URL (if ever present)
        if hasattr(result, "video_url") and result.video_url:
            url = self._coerce_url_string(result.video_url)
            if url and self._is_plausible_video_url(url):
                return url

        if hasattr(result, "content") and result.content is not None:
            content = result.content
            if isinstance(content, str):
                u = content.strip()
                if u.startswith("http"):
                    return u if self._is_plausible_video_url(u) else None

            # Single SDK Content object: read fields directly (do not iterate the model)
            for attr in ("video_url", "file_url", "last_frame_url"):
                if hasattr(content, attr):
                    raw = getattr(content, attr, None)
                    url = self._coerce_url_string(raw)
                    if url:
                        return url

            if hasattr(content, "model_dump"):
                dumped = content.model_dump(mode="json")
                found = self._extract_video_url_from_jsonish(dumped)
                if found:
                    return found

            if isinstance(content, (list, tuple)):
                for item in content:
                    if isinstance(item, str) and item.startswith("http"):
                        if self._is_plausible_video_url(item):
                            return item
                    if hasattr(item, "video_url"):
                        url = self._coerce_url_string(getattr(item, "video_url", None))
                        if url:
                            return url
                    if hasattr(item, "url"):
                        url = self._coerce_url_string(getattr(item, "url", None))
                        if url:
                            return url
                    if isinstance(item, dict):
                        found = self._extract_video_url_from_jsonish(item)
                        if found:
                            return found

        # Try output / data wrappers
        for field_name in ("output", "data"):
            if hasattr(result, field_name):
                field = getattr(result, field_name)
                if isinstance(field, str) and field.startswith("http"):
                    return field
                if hasattr(field, "video_url"):
                    url = self._coerce_url_string(getattr(field, "video_url", None))
                    if url:
                        return url
                if isinstance(field, dict):
                    found = self._extract_video_url_from_jsonish(field)
                    if found:
                        return found

        if hasattr(result, "model_dump"):
            found = self._extract_video_url_from_jsonish(result.model_dump(mode="json"))
            if found:
                return found

        return None

    async def get_video_task_status(self, task_id: str) -> Dict[str, Any]:
        logger.info("get_video_task_status request: task_id=%s", task_id)
        try:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, lambda: self._get_video_task_status_sync(task_id))
        except Exception as exc:
            logger.error("get_video_task_status failed: %s", exc)
            raise VolcengineAPIError(f"get_video_task_status failed: {exc}") from exc

    async def wait_for_video(
        self,
        task_id: str,
        max_wait_seconds: int = 300,
        poll_interval: int = 3,
    ) -> str:
        logger.info("wait_for_video start: task_id=%s max_wait=%s", task_id, max_wait_seconds)
        start_time = time.time()

        while True:
            if time.time() - start_time > max_wait_seconds:
                raise VolcengineAPIError(f"video generation timed out after {max_wait_seconds}s")

            status_info = await self.get_video_task_status(task_id)
            status = status_info["status"]

            if status == "succeeded":
                video_url = status_info.get("video_url")
                if video_url:
                    return video_url
                # Task succeeded but URL not yet propagated — keep polling
                logger.info("Task succeeded, waiting for URL to propagate...")
            elif status == "failed":
                raise VolcengineAPIError(f"video generation failed: {status_info.get('error', 'unknown error')}")
            # else: pending or processing — keep polling

            await asyncio.sleep(poll_interval)


_client: Optional[VolcengineClient] = None


def get_volcengine_client() -> VolcengineClient:
    global _client
    if _client is None:
        _client = VolcengineClient()
    return _client
