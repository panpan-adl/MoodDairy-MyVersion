"""Celery integration for asynchronous diary extraction."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Optional

from dotenv import load_dotenv
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker

try:
    from celery import Celery
    from celery.utils.log import get_task_logger
except ModuleNotFoundError:
    Celery = None

    def get_task_logger(name: str):
        return logging.getLogger(name)


load_dotenv()

logger = get_task_logger(__name__)
CELERY_INSTALLED = Celery is not None


if CELERY_INSTALLED:
    celery_app = Celery(
        "diary_extraction",
        broker=os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0"),
        backend=os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/0"),
    )
    celery_app.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="Asia/Shanghai",
        enable_utc=True,
        task_track_started=True,
        task_time_limit=300,
        task_soft_time_limit=240,
        task_acks_late=True,
        worker_prefetch_multiplier=1,
        result_expires=3600,
        task_routes={
            "app.celery_app.extract_diary_task": {"queue": "extraction"},
        },
        task_default_queue="default",
        task_default_exchange="default",
        task_default_routing_key="default",
    )
else:
    celery_app = None
    logger.warning("Celery is not installed; extraction tasks will be skipped.")


def extraction_queue_available() -> bool:
    """Return whether the Celery runtime is available in this environment."""

    return CELERY_INSTALLED


# ---------------------------------------------------------------------------
# Per-worker engine lifecycle
#
# IMPORTANT: on Windows the default celery prefork pool uses spawn (no
# forking), so each worker process calls this module's top-level code fresh.
# The engine is created once per worker and reused for every task invocation
# inside that worker.  Never call asyncio.run() more than once per process –
# creating/destroying nested event loops is what triggers the
# ProactorEventLoop AttributeError in asyncpg.
# ---------------------------------------------------------------------------

_worker_engine: Optional[AsyncEngine] = None
_worker_session_maker: Optional[sessionmaker[AsyncSession]] = None


def _init_worker_engine() -> tuple[AsyncEngine, sessionmaker[AsyncSession]]:
    """Create (or return) the process-wide engine for the Celery worker."""
    global _worker_engine, _worker_session_maker

    if _worker_engine is not None:
        return _worker_engine, _worker_session_maker

    database_url = os.getenv("DATABASE_URL")
    if not database_url:
        raise ValueError("DATABASE_URL environment variable is required for extraction tasks")

    _worker_engine = create_async_engine(
        database_url,
        echo=False,
        pool_pre_ping=True,
        pool_size=5,
        max_overflow=10,
    )
    _worker_session_maker = sessionmaker(
        _worker_engine,
        class_=AsyncSession,
        expire_on_commit=False,
    )
    return _worker_engine, _worker_session_maker


async def _dispose_worker_engine() -> None:
    """Dispose of the worker engine gracefully, suppressing Windows Proactor errors."""
    global _worker_engine, _worker_session_maker

    if _worker_engine is not None:
        try:
            await _worker_engine.dispose()
        except AttributeError:
            pass
        except RuntimeError:
            pass
        # Swallow ProactorEventLoop errors on Windows: when the loop is already
        # shutting down, asyncpg connection finalisers may still try to write to
        # the now-null proactor.  SQLAlchemy has already done the right thing by
        # this point, so we silently absorb the noise.
        except OSError:
            pass
        finally:
            _worker_engine = None
            _worker_session_maker = None


def _extract_sync(diary_id: int, force: bool) -> dict | None:
    """
    Run a single extraction inside the Celery worker.

    The worker is already running inside an event loop (either the celery
    main loop or the celery subprocess loop on Windows), so we simply get
    the running loop and run our coroutine on it – no new asyncio.run().
    """
    engine, session_maker = _init_worker_engine()

    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        # Fallback for the unusual case where no loop is running yet
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        is_ours = True
    else:
        is_ours = False

    async def _do_extract() -> dict | None:
        async with session_maker() as session:
            from app.services.extraction_service import ExtractionService

            extraction_service = ExtractionService(session)
            return await extraction_service.extract_diary(diary_id, force)

    try:
        return loop.run_until_complete(_do_extract())
    finally:
        if is_ours:
            loop.run_until_complete(_dispose_worker_engine())
            loop.close()


async def _record_failure_async(session_maker, diary_id: int, error: Exception, attempt: int) -> None:
    """Record a failure into the ExtractionJob table."""

    async with session_maker() as session:
        from app.models.database import ExtractionJob

        stmt = (
            select(ExtractionJob)
            .where(ExtractionJob.diary_id == diary_id)
            .order_by(ExtractionJob.created_at.desc())
            .limit(1)
        )
        result = await session.execute(stmt)
        job = result.scalar_one_or_none()

        if job:
            job.attempts = attempt
            job.error_message = str(error)
            job.error_code = type(error).__name__
            await session.commit()


def _record_failure_sync(session_maker, diary_id: int, error: Exception, attempt: int) -> None:
    """Synchronous wrapper around _record_failure_async, reuses the running loop."""

    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        is_ours = True
    else:
        is_ours = False

    try:
        loop.run_until_complete(_record_failure_async(session_maker, diary_id, error, attempt))
    finally:
        if is_ours:
            loop.close()


def _execute_task(diary_id: int, force: bool, retries: int, max_retries: int) -> dict | None:
    logger.info(
        "Starting extraction task: diary_id=%s force=%s attempt=%s",
        diary_id,
        force,
        retries + 1,
    )

    try:
        result = _extract_sync(diary_id, force)
        logger.info("Extraction task completed: diary_id=%s", diary_id)
        return result
    except Exception as exc:
        logger.error(
            "Extraction task failed: diary_id=%s error=%s attempt=%s/%s",
            diary_id,
            exc,
            retries + 1,
            max_retries + 1,
        )

        try:
            _, session_maker = _init_worker_engine()
            _record_failure_sync(session_maker, diary_id, exc, retries + 1)
        except Exception as record_error:
            logger.warning("Failed to record extraction task error: %s", record_error)

        raise


if CELERY_INSTALLED:

    @celery_app.task(
        bind=True,
        name="app.celery_app.extract_diary_task",
        max_retries=3,
        default_retry_delay=60,
        autoretry_for=(Exception,),
        retry_backoff=True,
        retry_backoff_max=600,
        retry_jitter=True,
    )
    def extract_diary_task(self, diary_id: int, force: bool = False):
        """Celery task that executes diary extraction."""

        return _execute_task(diary_id, force, self.request.retries, self.max_retries)

else:

    def extract_diary_task(diary_id: int, force: bool = False):
        """Fallback stub used when Celery is unavailable."""

        raise RuntimeError(
            f"Celery is not installed; cannot execute extraction for diary_id={diary_id}, force={force}"
        )


def trigger_extraction(diary_id: int, force: bool = False, delay: int = 0):
    """Queue a diary extraction task if Celery is available."""

    if not CELERY_INSTALLED:
        logger.warning(
            "Skipping extraction trigger because Celery is unavailable: diary_id=%s force=%s",
            diary_id,
            force,
        )
        return None

    if delay > 0:
        logger.info(
            "Queueing delayed extraction task: diary_id=%s force=%s delay=%ss",
            diary_id,
            force,
            delay,
        )
        return extract_diary_task.apply_async(args=[diary_id, force], countdown=delay)

    logger.info("Queueing extraction task: diary_id=%s force=%s", diary_id, force)
    return extract_diary_task.delay(diary_id, force)
