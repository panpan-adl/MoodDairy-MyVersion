"""Recover exact generated images from provider URLs or a read-only Coil cache copy.

Dry-run by default. --apply changes only successfully recovered media URLs and
writes the original URLs to a local rollback manifest before committing.
"""
import argparse
import asyncio
import hashlib
import json
import sys
import tarfile
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx
from sqlalchemy import select
from app.config import settings
from app.database import async_session_maker
from app.models.database import DiaryMedia
from app.drawing.generated_media import save_image_backup


async def recover(args):
    cache = tarfile.open(args.cache) if args.cache else None
    changes, unavailable = [], []
    try:
        async with async_session_maker() as db, httpx.AsyncClient(
            timeout=20, follow_redirects=True, trust_env=False
        ) as client:
            rows = (await db.execute(select(DiaryMedia).where(
                DiaryMedia.media_type == "image"
            ))).scalars().all()
            for media in rows:
                url = media.media_url or ""
                if not (urlsplit(url).hostname or "").endswith(".volces.com"):
                    continue
                content, source = None, "cache"
                if cache:
                    key = hashlib.sha256(url.encode()).hexdigest()
                    try:
                        content = cache.extractfile(f"cache/image_cache/{key}.1").read()
                    except KeyError:
                        pass
                if content is None:
                    source = "provider"
                    try:
                        response = await client.get(url)
                        response.raise_for_status()
                        content = response.content
                    except httpx.HTTPError:
                        unavailable.append(media.id)
                        continue
                target = save_image_backup(url, content)
                replacement = "/media/" + target.relative_to(Path(settings.upload_dir)).as_posix()
                changes.append(dict(id=media.id, diary_id=media.diary_id,
                                    original=url, replacement=replacement, source=source))
                if args.apply:
                    media.media_url = replacement
            manifest = Path("logs") / ("media-recovery-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
            manifest.parent.mkdir(exist_ok=True)
            manifest.write_text(json.dumps(dict(applied=args.apply, changes=changes,
                                               unavailable=unavailable), indent=2), encoding="utf-8")
            if args.apply:
                await db.commit()
            print(json.dumps(dict(applied=args.apply, recovered=len(changes),
                                  cache_recovered=sum(c["source"] == "cache" for c in changes),
                                  unavailable=unavailable, manifest=str(manifest))))
    finally:
        if cache:
            cache.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", help="Read-only app cache tar archive")
    parser.add_argument("--apply", action="store_true")
    asyncio.run(recover(parser.parse_args()))
