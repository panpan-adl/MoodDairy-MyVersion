"""Verify that a real editor save completes background extraction without Redis."""
import asyncio
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT / '.env')
from app.database import get_db
from app.models.database import User
from app.celery_app import extraction_queue_available


async def main():
    name = 'qa_dispatch_' + uuid4().hex[:12]
    uid = did = None
    result = {'passed': False, 'queue_available': await asyncio.to_thread(extraction_queue_available)}
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000', timeout=15, trust_env=False) as c:
        try:
            r = await c.post('/api/users/register', json={'username': name, 'password': uuid4().hex})
            r.raise_for_status()
            uid = r.json()['id']
            c.headers['Authorization'] = 'Bearer ' + r.json()['access_token']
            started = time.monotonic()
            r = await c.post('/diaries/', json={'user_id': uid,
                'diary_date': str(datetime.now(ZoneInfo('Asia/Shanghai')).date()),
                'title': '后台提取验收', 'content': '今天完成了实验报告，和朋友散步后轻松了许多。'})
            r.raise_for_status()
            did = r.json()['id']
            result['save_seconds'] = round(time.monotonic() - started, 2)
            assert result['save_seconds'] < 5, 'Background dispatch delayed diary saving'
            for _ in range(90):
                r = await c.get(f'/diaries/{did}/extract/status')
                r.raise_for_status()
                state = r.json()
                result['status'] = state
                if state.get('status') == 'succeeded':
                    result['passed'] = True
                    break
                if state.get('status') == 'failed':
                    raise AssertionError('Background extraction failed')
                await asyncio.sleep(1)
            assert result['passed'], 'Background extraction did not finish'
        finally:
            if did is not None:
                r = await c.delete(f'/diaries/{did}')
                r.raise_for_status()
            if uid is not None:
                async for db in get_db():
                    user = await db.get(User, uid)
                    assert user is not None and user.username == name
                    await db.delete(user)
                    await db.commit()
            result['temporary_account_removed'] = uid is not None
            (ROOT / 'logs/extraction-dispatch-smoke.json').write_text(
                json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(result, ensure_ascii=True), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
