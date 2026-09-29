"""Manual database probe for legacy media table access.

This script is intentionally excluded from automated pytest pass/fail status.
Run it directly when you need to inspect a local database schema.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest
from sqlalchemy import text

sys.path.insert(0, str(Path(__file__).parent))

from app.database.connection import get_db  # noqa: E402

pytestmark = pytest.mark.skip(reason="manual database probe; run this file directly when needed")


async def run_table_probe() -> None:
    """Check whether the legacy media_files table exists and is queryable."""

    print("=" * 60)
    print("Manual probe: media_files table access")
    print("=" * 60)

    async for db in get_db():
        print("\n[1] Check whether public.media_files exists")
        result = await db.execute(
            text(
                """
                SELECT EXISTS (
                    SELECT FROM information_schema.tables
                    WHERE table_schema = 'public'
                    AND table_name = 'media_files'
                )
                """
            )
        )
        exists = result.scalar()
        print(f"exists: {exists}")

        print("\n[2] Query public.media_files row count")
        try:
            result = await db.execute(text("SELECT COUNT(*) FROM public.media_files"))
            count = result.scalar()
            print(f"table is accessible, row count: {count}")
        except Exception as exc:
            print(f"table is not accessible: {exc}")
            await db.rollback()

        print("\n[3] Show current schema")
        result = await db.execute(text("SELECT current_schema()"))
        print(f"current_schema: {result.scalar()}")

        print("\n[4] Show search_path")
        result = await db.execute(text("SHOW search_path"))
        print(f"search_path: {result.scalar()}")
        break


if __name__ == "__main__":
    asyncio.run(run_table_probe())
