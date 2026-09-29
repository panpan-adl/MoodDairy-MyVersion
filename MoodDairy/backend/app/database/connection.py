"""Database connection helpers with primary and fallback URLs."""

from __future__ import annotations

import logging
import os
import time
from typing import AsyncGenerator

from dotenv import load_dotenv
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import declarative_base

load_dotenv()

logger = logging.getLogger(__name__)

DEFAULT_FALLBACK_DATABASE_URL = "postgresql+asyncpg://app_user:CHANGE_ME@47.104.168.245:5432/app_db"
DATABASE_URL = os.getenv("DATABASE_URL", DEFAULT_FALLBACK_DATABASE_URL)
DATABASE_URL_FALLBACK = os.getenv("DATABASE_URL_FALLBACK", DEFAULT_FALLBACK_DATABASE_URL)

DB_CONNECTION_CHECK_INTERVAL_SEC = int(os.getenv("DB_CONNECTION_CHECK_INTERVAL_SEC", "15"))
DB_PERMISSION_PROBE_ENABLED = os.getenv("DB_PERMISSION_PROBE_ENABLED", "1").strip().lower() not in {
    "0",
    "false",
    "no",
}
DB_PERMISSION_PROBE_SQL = os.getenv("DB_PERMISSION_PROBE_SQL", "SELECT 1 FROM public.diaries LIMIT 1")


def _build_engine(database_url: str):
    return create_async_engine(
        database_url,
        echo=True,
        pool_pre_ping=True,
        pool_size=5,
        max_overflow=10,
    )


def _build_session_maker(db_engine):
    return async_sessionmaker(
        db_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autocommit=False,
        autoflush=False,
    )


_primary_engine = _build_engine(DATABASE_URL)
_primary_session_maker = _build_session_maker(_primary_engine)

_fallback_engine = None
_fallback_session_maker = None
if DATABASE_URL_FALLBACK and DATABASE_URL_FALLBACK != DATABASE_URL:
    _fallback_engine = _build_engine(DATABASE_URL_FALLBACK)
    _fallback_session_maker = _build_session_maker(_fallback_engine)

engine = _primary_engine
async_session_maker = _primary_session_maker
_active_db_url = DATABASE_URL
_last_check_ts = 0.0

Base = declarative_base()


async def _probe_session(session_maker: async_sessionmaker[AsyncSession], db_url: str) -> bool:
    """Probe connectivity and optional table permissions."""

    try:
        async with session_maker() as session:
            await session.execute(text("SELECT 1"))
            if DB_PERMISSION_PROBE_ENABLED:
                await session.execute(text(DB_PERMISSION_PROBE_SQL))
        return True
    except Exception as exc:
        logger.warning("Database probe failed: url=%s error=%s", db_url, exc)
        return False


def _activate_primary() -> None:
    global engine, async_session_maker, _active_db_url
    engine = _primary_engine
    async_session_maker = _primary_session_maker
    _active_db_url = DATABASE_URL


def _activate_fallback() -> None:
    global engine, async_session_maker, _active_db_url
    if _fallback_engine is None or _fallback_session_maker is None:
        return
    engine = _fallback_engine
    async_session_maker = _fallback_session_maker
    _active_db_url = DATABASE_URL_FALLBACK


async def _ensure_active_connection(force: bool = False) -> bool:
    """Ensure the current session maker points to a usable database."""

    global _last_check_ts

    now = time.monotonic()
    if not force and (now - _last_check_ts) < DB_CONNECTION_CHECK_INTERVAL_SEC:
        return True
    _last_check_ts = now

    if await _probe_session(_primary_session_maker, DATABASE_URL):
        if _active_db_url != DATABASE_URL:
            logger.warning("Database connection switched back to primary DATABASE_URL")
        _activate_primary()
        return True

    if _fallback_session_maker and await _probe_session(_fallback_session_maker, DATABASE_URL_FALLBACK):
        if _active_db_url != DATABASE_URL_FALLBACK:
            logger.warning("Primary database unavailable; switched to DATABASE_URL_FALLBACK")
        _activate_fallback()
        return True

    logger.error("Primary and fallback databases are both unavailable")
    return False


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """Yield an active async database session."""

    if not await _ensure_active_connection():
        raise RuntimeError("Database connection unavailable: primary and fallback probes both failed")

    async with async_session_maker() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


async def init_db() -> None:
    """Validate the configured database connection at startup."""

    await _ensure_active_connection(force=True)


async def close_db() -> None:
    """Dispose all database engines gracefully.

    On Windows with ProactorEventLoop, asyncpg connections may raise
    AttributeError when closed after the event loop begins shutdown
    (the proactor is None). Dispose has already invalidated the pool;
    the error is safe to ignore.
    """

    engines = {_primary_engine}
    if _fallback_engine is not None:
        engines.add(_fallback_engine)

    for db_engine in engines:
        try:
            await db_engine.dispose()
        except AttributeError:
            pass


async def check_db_connection() -> bool:
    """Check whether either the primary or fallback database is usable."""

    is_ok = await _ensure_active_connection(force=True)
    if not is_ok:
        print("DB connection failed: primary and fallback are both unavailable")
        return False

    print(f"DB connection active: {_active_db_url}")
    return True
