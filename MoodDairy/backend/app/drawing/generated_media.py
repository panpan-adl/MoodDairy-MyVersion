"""Archive generated images before exposing expiring provider URLs to callers."""
from __future__ import annotations

import asyncio
import hashlib
import io
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from PIL import Image

from app.config import settings
from app.services.media_service import MediaService


def image_backup_path(source_url: str) -> Path:
    parsed = urlsplit(source_url)
    # Signatures change; the provider object identity does not.
    identity = f"{parsed.netloc}{parsed.path}"
    key = hashlib.sha256(identity.encode()).hexdigest()
    return Path(settings.upload_dir) / "generated" / "images" / f"{key}.jpg"


def save_image_backup(source_url: str, content: bytes) -> Path:
    # Reject error documents and truncated downloads before publishing anything.
    with Image.open(io.BytesIO(content)) as image:
        image.verify()
    target = image_backup_path(source_url)
    target.parent.mkdir(parents=True, exist_ok=True)
    # The image bytes keep their original format; use a matching extension.
    with Image.open(io.BytesIO(content)) as image:
        suffix = {"PNG": ".png", "WEBP": ".webp", "GIF": ".gif"}.get(image.format, ".jpg")
    target = target.with_suffix(suffix)
    import uuid
    temporary = target.with_name(f"{target.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(content)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


async def persist_generated_image(source_url: str) -> str:
    """Fail generation if archival fails, instead of saving a 24-hour URL."""
    async with httpx.AsyncClient(timeout=60, follow_redirects=True, trust_env=False) as client:
        response = await client.get(source_url)
        response.raise_for_status()
    target = await asyncio.to_thread(save_image_backup, source_url, response.content)
    service = await asyncio.to_thread(MediaService, upload_dir=settings.upload_dir)
    result = await service.upload_media_bytes(
        file_bytes=response.content,
        media_type="image",
        content_type=Image.MIME.get(Image.open(io.BytesIO(response.content)).format, "image/jpeg"),
        filename=target.name,
    )
    return result["url"]
