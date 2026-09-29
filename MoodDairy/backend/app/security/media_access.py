"""Resolve stored media URLs to time-limited presigned URLs when OSS is private."""

from __future__ import annotations

import logging
import os
from typing import Optional
from urllib.parse import urlparse

from app.config import settings

logger = logging.getLogger(__name__)

_PRESIGN_SECONDS = int(os.getenv("OSS_PRESIGN_EXPIRE_SECONDS", "3600"))
_USE_PRESIGNED = os.getenv("OSS_USE_PRESIGNED_URLS", "1").strip().lower() in {"1", "true", "yes"}


def _extract_oss_key(stored_url: str) -> Optional[str]:
    if not stored_url:
        return None
    if stored_url.startswith("/"):
        return None
    parsed = urlparse(stored_url)
    path = parsed.path.lstrip("/")
    prefix = (settings.oss_prefix or "media/").rstrip("/")
    if path.startswith(prefix) or path.startswith("media/"):
        return path
    if settings.oss_public_base_url and stored_url.startswith(settings.oss_public_base_url):
        return stored_url[len(settings.oss_public_base_url.rstrip("/")) + 1 :]
    return None


def resolve_media_url(stored_url: Optional[str]) -> Optional[str]:
    """Return presigned GET URL for private OSS objects; pass through local paths unchanged."""
    if not stored_url:
        return stored_url
    if stored_url.startswith("/") or stored_url.startswith("uploads/"):
        return stored_url
    if not settings.oss_enabled or not _USE_PRESIGNED:
        return stored_url

    key = _extract_oss_key(stored_url)
    if not key:
        return stored_url

    try:
        from app.services.media_service import MediaService

        service = MediaService(upload_dir=settings.upload_dir)
        if service.oss_service is None:
            return stored_url
        return service.oss_service.generate_presigned_get_url(key, expires=_PRESIGN_SECONDS)
    except Exception as exc:
        logger.warning("Presigned URL generation failed for %s: %s", stored_url, exc)
        return stored_url


def resolve_media_item_response(item):
    """Apply presigned URLs to a MediaItemResponse."""
    from app.models.schemas import MediaItemResponse

    if not isinstance(item, MediaItemResponse):
        item = MediaItemResponse.model_validate(item)
    return item.model_copy(
        update={
            "media_url": resolve_media_url(item.media_url),
            "thumbnail_url": resolve_media_url(item.thumbnail_url),
        }
    )


def resolve_aggregated_media_item(item):
    """Apply presigned URLs to a MediaItemAggregatedResponse."""
    from app.models.schemas import MediaItemAggregatedResponse

    if not isinstance(item, MediaItemAggregatedResponse):
        item = MediaItemAggregatedResponse.model_validate(item)
    return item.model_copy(
        update={
            "url": resolve_media_url(item.url) or item.url,
            "thumbnail_url": resolve_media_url(item.thumbnail_url),
        }
    )


def to_diary_response(diary):
    """Build DiaryResponse from ORM/model with presigned media URLs."""
    from app.models.schemas import DiaryResponse

    response = DiaryResponse.model_validate(diary)
    if response.media_items:
        response = response.model_copy(
            update={
                "media_items": [resolve_media_item_response(m) for m in response.media_items]
            }
        )
    return response
