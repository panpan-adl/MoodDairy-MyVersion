"""Live five-module acceptance using a disposable account and synthetic content.

Does not bind search keys, execute paid searches, or generate paid artwork.
Only the temporary account created by this run is deleted during cleanup.
"""
import asyncio
from datetime import datetime, timedelta
import io
import json
from pathlib import Path
import sys
import time
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
RESULTS = []
REPORT = ROOT / 'logs' / ('five-module-search-confirmation.json' if '--confirm-search-only' in sys.argv else 'five-module-smoke.json')


async def main():
    today = datetime.now(ZoneInfo('Asia/Shanghai')).date()
    marker = 'qa_modules_' + uuid4().hex[:12]
    uid = None
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000', timeout=110, trust_env=False) as c:
        async def check(module, name, method, path, expected=(200,), validate=None, **kwargs):
            started = time.monotonic()
            row = dict(module=module, name=name, passed=False)
            try:
                r = await c.request(method, path, **kwargs)
                row['status'] = r.status_code
                row['passed'] = r.status_code in expected and (validate(r) if validate else True)
                return r if row['passed'] else None
            except Exception as exc:
                row['error'] = type(exc).__name__
                return None
            finally:
                row['seconds'] = round(time.monotonic() - started, 2)
                RESULTS.append(row)
                print(json.dumps(row, ensure_ascii=True), flush=True)

        async def stream(name, message, validate, conversation=None, confirmation=None):
            payload = dict(user_id=uid, conversation_id=conversation or marker, message=message)
            if confirmation:
                payload['confirmation'] = confirmation
            def parsed(response):
                return [json.loads(line[5:].strip()) for line in response.text.splitlines() if line.startswith('data:')]
            r = await check('support', name, 'POST', '/chat/stream', json=payload,
                            validate=lambda r: validate(parsed(r)))
            return parsed(r) if r else []

        try:
            await check('foundation', 'health/database', 'GET', '/health',
                        validate=lambda r: r.json().get('database') == 'connected')
            r = await check('foundation', 'temporary registration', 'POST', '/api/users/register', (201,),
                            json=dict(username=marker, password=uuid4().hex, nickname='模块验收临时账号'))
            if r is None:
                raise RuntimeError('Disposable account could not be created')
            uid = r.json()['id']
            (ROOT / 'logs' / 'five-module-owned-account.json').write_text(json.dumps(dict(id=uid, username=marker)), encoding='utf-8')
            c.headers['Authorization'] = 'Bearer ' + r.json()['access_token']
            if '--confirm-search-only' in sys.argv:
                events = await stream('search preview requests confirmation', '帮我找关于焦虑的心理书籍',
                                      lambda es: any(e.get('type') == 'awaiting_confirmation' for e in es))
                plan = next(e['plan_id'] for e in events if e.get('type') == 'awaiting_confirmation')
                await stream('confirm without key gives explicit configuration feedback', '',
                             lambda es: any(e.get('type') == 'tool_result' and
                                            (e.get('data') or {}).get('error_code') == 'NO_KEY' for e in es),
                             confirmation=dict(plan_id=plan, approved=True))
                if any(not row['passed'] for row in RESULTS):
                    raise AssertionError('Search confirmation acceptance failed; see the local JSON report')
                return
            body = dict(date=str(today), record='仅用于验收：今天准备实验报告，想把安排理清楚。' + marker,
                        request_id=str(uuid4()))
            await check('expression', 'retired quick record endpoint explains replacement',
                        'POST', '/agent/record', (410,), json=body)
            # Task planning no longer writes diaries. Exercise the actual editor API.
            record = await check('expression', 'editor saves diary', 'POST', '/diaries/', (201,),
                                 json=dict(user_id=uid, diary_date=str(today),
                                           title='模块验收日记', content=body['record']))
            did = record.json()['id'] if record else None
            if did:
                await check('expression', 'saved diary decrypts correctly', 'GET', f'/diaries/id/{did}',
                            validate=lambda r: marker in r.json().get('content', ''))
                image = io.BytesIO()
                Image.new('RGB', (32, 32), '#9aedcf').save(image, 'PNG')
                upload = await check('expression', 'synthetic diary image upload', 'POST', '/media/upload', (200, 201),
                                     data=dict(diary_id=str(did), media_type='image'),
                                     files={'file': ('synthetic.png', image.getvalue(), 'image/png')})
                if upload:
                    media = upload.json()
                    await check('expression', 'image attachment saved with diary', 'PUT', f'/diaries/{did}/media',
                                json={'items': [dict(asset_id=media['media_id'], media_type='image', sort_order=0)]})
                await check('memory', 'highlight and little joy save', 'PUT', f'/diaries/{did}',
                            json=dict(is_highlight=1, is_little_joy=1))
            await check('expression', 'blank record rejected', 'POST', '/agent/record', (422,),
                        json={**body, 'record': '  ', 'request_id': str(uuid4())})
            await check('review', 'calendar contains saved date', 'GET', '/diaries/dates-with-diary',
                        params=dict(year=today.year, month=today.month),
                        validate=lambda r: str(today) in json.dumps(r.json()))
            await check('review', 'encrypted diary keyword search', 'GET', '/diaries/search', params={'query': marker},
                        validate=lambda r: any(d['id'] == did for d in r.json()))
            for path in ('highlights', 'little-joys'):
                await check('review', path + ' contains saved diary', 'GET', '/diaries/' + path,
                            validate=lambda r: any(d['id'] == did for d in r.json()))
            await check('review', 'growth portrait loads', 'GET', '/insights/growth-portrait')
            await check('review', 'growth portrait invalid window rejected', 'GET', '/insights/growth-portrait',
                        (400, 422), params={'days': 1})
            for text, expected_id in [('我想测一下MBTI', 'mbti_simplified_v1'), ('我想做心理测试', '')]:
                await stream('specific/ambiguous test entry: ' + (expected_id or 'choice'), text,
                             lambda events, test=expected_id: any(e.get('action') == 'offer_tests' and
                             e.get('payload', {}).get('test_id') == test for e in events))
            await stream('relaxation entry does not auto-play', '打开白噪音',
                         lambda events: any(e.get('action') == 'open_relax' for e in events))
            await check('support', 'unconfigured search account feedback', 'GET', '/social-search/status',
                        validate=lambda r: r.json().get('bound') is False or r.json().get('has_key') is False)
            for i, text in enumerate(['帮我找几本关于焦虑的心理书籍', '帮我找关于心理健康的纪录片']):
                conv = marker + '_search_' + str(i)
                events = await stream('psychological resource requires fee confirmation', text,
                                      lambda es: any(e.get('type') == 'awaiting_confirmation' for e in es), conv)
                plan = next((e['plan_id'] for e in events if e.get('type') == 'awaiting_confirmation'), None)
                if plan:
                    await stream('cancel resource search', '', lambda es: any(
                        e.get('type') == 'tool_status' and e.get('status') == 'cancelled' for e in es), conv,
                        dict(plan_id=plan, approved=False))
            await stream('ordinary emotion chat returns reply without paid search',
                         '这是一条虚构验收消息：我今天有点累，想和你聊聊，请简短回应。',
                         lambda es: any(e.get('type') == 'done' and e.get('final_state') == 'success' for e in es)
                         and not any(e.get('tool') == 'search_social_content' or
                                     e.get('type') == 'awaiting_confirmation' for e in es))
            draft = await check('support', 'real AI study/work plan generation', 'POST', '/agent/plan',
                                json=dict(date=str(today), record='仅用于测试：准备实验报告，整理实验结果，再写一段结论。',
                                          energy='normal', available_minutes=60),
                                validate=lambda r: bool(r.json().get('tasks')))
            tasks = draft.json()['tasks'] if draft else [dict(title='整理测试报告', domain='study', minutes=20, next_step='列出三条结果')]
            adoption = dict(date=str(today), record='仅用于验收的安排', energy='normal', available_minutes=60,
                            request_id=str(uuid4()), tasks=tasks)
            adopted = await check('support', 'confirm proposed plan', 'POST', '/agent/adopt', json=adoption)
            if adopted and adopted.json().get('tasks'):
                task = adopted.json()['tasks'][0]
                await check('support', 'confirm retry does not duplicate tasks', 'POST', '/agent/adopt', json=adoption,
                            validate=lambda r: [t['id'] for t in r.json()['tasks']] == [t['id'] for t in adopted.json()['tasks']])
                await check('support', 'modify task and complete', 'PATCH', f"/agent/tasks/{task['id']}",
                            json=dict(title='已修改的测试任务', minutes=15, is_done=True),
                            validate=lambda r: r.json()['is_done'] and r.json()['minutes'] == 15)
            review = await check('review', 'real evening review generation', 'POST', '/agent/review',
                                 json=dict(date=str(today), reflection='仅用于验收：完成了实验结果整理，想适当休息。'),
                                 validate=lambda r: bool(r.json().get('content')))
            await check('review', 'save evening review to diary', 'POST', '/agent/review/save',
                        json=dict(date=str(today), request_id=str(uuid4()),
                                  content=review.json()['content'] if review else '仅用于验收的复盘。'))
            await check('support', 'historical tasks load', 'GET', '/agent/tasks/history')
            await check('support', 'relax music recommendations', 'GET', '/music/recommendations',
                        params=dict(mood='calm', limit=2), validate=lambda r: bool(r.json().get('tracks')))
            for path in ('/drawing/mode', '/drawing/enabled', '/drawing/sd-enabled'):
                await check('support', 'creation capability status ' + path, 'GET', path)
            await check('support', 'removed personal video endpoint absent', 'GET', '/personal-videos', (404,))
            await check('memory', 'emotion space loads for new account', 'GET', '/emotion-space')
            await check('memory', 'future letters load for new account', 'GET', '/future-letters')
        finally:
            output = dict(results=RESULTS, passed=sum(r['passed'] for r in RESULTS),
                          failed=sum(not r['passed'] for r in RESULTS))
            REPORT.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
            if uid is not None:
                from dotenv import load_dotenv
                load_dotenv(ROOT / '.env')
                from app.database import get_db
                from app.models.database import User, Diary
                from sqlalchemy import select
                async for db in get_db():
                    ids = (await db.execute(select(Diary.id).where(Diary.user_id == uid))).scalars().all()
                for diary_id in ids:
                    response = await c.delete(f'/diaries/{diary_id}')
                    response.raise_for_status()
                async for db in get_db():
                    user = await db.get(User, uid)
                    if user and user.username == marker:
                        await db.delete(user)
                        await db.commit()
                        (ROOT / 'logs' / 'five-module-owned-account.json').unlink(missing_ok=True)
                        print('Disposable account and linked records removed', flush=True)
            output = dict(results=RESULTS, passed=sum(r['passed'] for r in RESULTS),
                          failed=sum(not r['passed'] for r in RESULTS))
            REPORT.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({k: output[k] for k in ('passed', 'failed')}), flush=True)
        if any(not row['passed'] for row in RESULTS):
            raise SystemExit(1)


if __name__ == '__main__':
    asyncio.run(main())
