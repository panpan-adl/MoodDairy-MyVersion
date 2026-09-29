import asyncio
import asyncpg
import os

async def check_remote_db():
    url = 'postgresql://app_user:sss3.1415926535@47.104.168.245:5432/app_db'
    print('Connecting to remote DB...')
    try:
        conn = await asyncpg.connect(url, timeout=10)
        print('Connected!')
        tables = await conn.fetch("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            ORDER BY table_name
        """)
        print(f'Tables ({len(tables)}):')
        for t in tables:
            print(f'  - {t[0]}')
        try:
            result = await conn.fetch('SELECT 1 FROM public.diaries LIMIT 1')
            print('Probe: diaries table accessible')
        except Exception as e:
            print(f'Probe diaries failed: {e}')
        try:
            result = await conn.fetch('SELECT 1 FROM public.media_files LIMIT 1')
            print('Probe: media_files table accessible')
        except Exception as e:
            print(f'Probe media_files failed: {e}')
        await conn.close()
    except Exception as e:
        print(f'Connection failed: {e}')

asyncio.run(check_remote_db())
