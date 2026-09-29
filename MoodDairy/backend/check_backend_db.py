import asyncio
import asyncpg
import sys
sys.path.insert(0, '.')
from app.database.connection import check_db_connection, DATABASE_URL, DATABASE_URL_FALLBACK, _active_db_url

async def main():
    print(f"DATABASE_URL: {DATABASE_URL}")
    print(f"DATABASE_URL_FALLBACK: {DATABASE_URL_FALLBACK}")
    ok = await check_db_connection()
    print(f"check_db_connection result: {ok}")

asyncio.run(main())
