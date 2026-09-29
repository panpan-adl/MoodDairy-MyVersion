import asyncio
import sys
sys.path.insert(0, '.')
from app.database.connection import check_db_connection, DATABASE_URL, DATABASE_URL_FALLBACK, _active_db_url
from app.database.connection import _primary_session_maker, _fallback_session_maker, engine
from sqlalchemy import text
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')

async def probe_db(label, session_maker):
    print(f'\n=== Probing {label} ===')
    try:
        async with session_maker() as session:
            await session.execute(text("SELECT 1"))
            print("  SELECT 1: OK")
            await session.execute(text("SELECT 1 FROM public.diaries LIMIT 1"))
            print("  SELECT 1 FROM diaries: OK")
            await session.execute(text("SELECT 1 FROM public.media_files LIMIT 1"))
            print("  SELECT 1 FROM media_files: OK")
            await session.execute(text("SELECT 1 FROM public.users LIMIT 1"))
            print("  SELECT 1 FROM users: OK")
            return True
    except Exception as e:
        print(f"  FAILED: {e}")
        return False

async def main():
    print(f"DATABASE_URL: {DATABASE_URL}")
    print(f"DATABASE_URL_FALLBACK: {DATABASE_URL_FALLBACK}")
    print(f"Primary session maker: {_primary_session_maker}")
    print(f"Fallback session maker: {_fallback_session_maker}")

    p_ok = await probe_db("PRIMARY", _primary_session_maker)
    f_ok = await probe_db("FALLBACK", _fallback_session_maker) if _fallback_session_maker else False

    print(f"\nPrimary DB OK: {p_ok}")
    print(f"Fallback DB OK: {f_ok}")
    print(f"\nActive DB: {_active_db_url}")
    print(f"Engine: {engine}")

asyncio.run(main())
