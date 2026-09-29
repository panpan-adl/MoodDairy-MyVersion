"""Repair grants for the configured application database role."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from urllib.parse import urlparse

import asyncpg
from dotenv import load_dotenv

DEFAULT_ADMIN_DATABASE_URL = ""


def _normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgresql+asyncpg://"):
        return database_url.replace("postgresql+asyncpg://", "postgresql://", 1)
    return database_url


def _quote_ident(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def _parse_username(database_url: str) -> str:
    parsed = urlparse(_normalize_database_url(database_url))
    if not parsed.username:
        raise ValueError(f"Unable to parse username from database url: {database_url}")
    return parsed.username


def _parse_database_name(database_url: str) -> str:
    parsed = urlparse(_normalize_database_url(database_url))
    database_name = parsed.path.lstrip("/")
    if not database_name:
        raise ValueError(f"Unable to parse database name from database url: {database_url}")
    return database_name


async def main() -> int:
    project_root = Path(__file__).resolve().parents[1]
    load_dotenv(project_root / ".env")

    primary_database_url = os.getenv("DATABASE_URL")
    if not primary_database_url:
        print("DATABASE_URL is required")
        return 1

    admin_database_url = (
        os.getenv("DATABASE_URL_ADMIN")
        or os.getenv("DATABASE_URL_FALLBACK")
        or DEFAULT_ADMIN_DATABASE_URL
        or primary_database_url
    )
    app_role = os.getenv("DATABASE_APP_ROLE") or _parse_username(primary_database_url)
    database_name = _parse_database_name(primary_database_url)
    quoted_role = _quote_ident(app_role)

    admin_conn = await asyncpg.connect(dsn=_normalize_database_url(admin_database_url))
    try:
        admin_user = await admin_conn.fetchval("select current_user")
        print(f"Connected as admin role: {admin_user}")

        statements = [
            f"GRANT CONNECT ON DATABASE {_quote_ident(database_name)} TO {quoted_role}",
            f"GRANT USAGE ON SCHEMA public TO {quoted_role}",
            f"GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO {quoted_role}",
            f"GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO {quoted_role}",
            f"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO {quoted_role}",
            f"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO {quoted_role}",
        ]

        for statement in statements:
            await admin_conn.execute(statement)

        print(f"Granted public schema access to role: {app_role}")
    finally:
        await admin_conn.close()

    primary_conn = await asyncpg.connect(dsn=_normalize_database_url(primary_database_url))
    try:
        primary_user = await primary_conn.fetchval("select current_user")
        await primary_conn.fetchval("select 1 from public.diaries limit 1")
        print(f"Verified table access as role: {primary_user}")
    finally:
        await primary_conn.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
