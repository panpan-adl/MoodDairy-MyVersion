"""Live model + PostgreSQL + Android acceptance using disposable synthetic data."""
import asyncio
import json
import os
from pathlib import Path
import subprocess
from android_instrumentation import run_instrumentation
import sys
from datetime import date
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT / '.env')
import httpx
from sqlalchemy import select
from app.database import get_db
from app.models.database import User, Diary, DiaryMedia, VoiceTranscription, DiaryMultimodalInsight
from app.security.field_crypto import encrypt_field, decrypt_field
from app.services.diary_service import DiaryService
from app.security.jwt_auth import create_access_token


async def main():
    ids, results = [], []
    username = 'qa_review_' + uuid4().hex[:10]
    cases = [('积极', '今天和朋友在公园散步，闻到桂花香，很开心。'),
             ('疲惫', '今天赶完了实验报告，很累，回家只想安静躺一会儿。'),
             ('混合', '完成了机器学习实验报告，很有成就感，但想到明天答辩又有些担心。'),
             ('短句', '烦。'), ('语音', ''), ('纯附件', '')]
    if os.getenv('DIARY_REVIEW_NATIVE_ONLY') == '1':
        cases = cases[:1]
    async for db in get_db():
        if os.getenv('DIARY_REVIEW_RESUME') == '1':
            user = (await db.execute(select(User).where(User.username.like('qa_review_%'),
                    User.password == 'disabled-test-account').order_by(User.id.desc()))).scalars().first()
            assert user and user.username.startswith('qa_review_')
            diaries = (await db.execute(select(Diary).where(Diary.user_id == user.id).order_by(Diary.id))).scalars().all()
            assert len(diaries) == len(cases)
            assert [(decrypt_field(d.content) or '') for d in diaries] == [text for _, text in cases]
            uid, username, ids = user.id, user.username, [d.id for d in diaries]
        else:
            user = User(username=username, password='disabled-test-account', status=1)
            db.add(user)
            await db.flush()
            uid = user.id
            for label, text in cases:
                diary = Diary(user_id=uid, title='', content=encrypt_field(text), diary_date=date.today())
                db.add(diary)
                await db.flush()
                ids.append(diary.id)
                if label == '语音':
                    db.add(VoiceTranscription(diary_id=diary.id, original_text=encrypt_field('今天练琴的时候卡了很久，最后弹顺了一小段。')))
                if label == '纯附件':
                    db.add(DiaryMedia(diary_id=diary.id, media_type='image', media_url='synthetic-attachment-no-network'))
            await db.commit()
    passed = False
    token = create_access_token(uid, username)
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000', headers={'Authorization': 'Bearer ' + token},
                                 timeout=140, trust_env=False) as client:
        try:
            for (label, text), diary_id in zip(cases, ids):
                route = f'/insights/diaries/{diary_id}/review'
                initial = await client.get(route)
                initial.raise_for_status()
                value = initial.json()
                if not value['body'] or value['stale']:
                    response = await client.post(route, json={'content_version': value['content_version']})
                    response.raise_for_status()
                    value = response.json()
                if label == '纯附件':
                    assert not value['has_text'] and value['body'] is None
                else:
                    assert value['body'] and not value['stale']
                    assert '你应该' not in value['body'] and '建议你' not in value['body']
                assert (await client.get(route)).json() == value
                results.append({'case': label, 'source': text or ('语音：今天练琴的时候卡了很久，最后弹顺了一小段。' if label == '语音' else '仅图片'),
                                'review': value['body'], 'question': value['question'], 'characters': len(value['body'] or ''),
                                'cached_read': True})
                print(json.dumps(results[-1], ensure_ascii=True), flush=True)
            # Authentication and owner isolation against the running server.
            assert (await client.get(f'/insights/diaries/{ids[0]}/review', headers={'Authorization': ''})).status_code == 401
            other = create_access_token(922337200000000001, 'qa-other')
            assert (await client.get(f'/insights/diaries/{ids[0]}/review', headers={'Authorization': 'Bearer ' + other})).status_code == 404
            async for db in get_db():
                insight = (await db.execute(select(DiaryMultimodalInsight).where(DiaryMultimodalInsight.diary_id == ids[0]))).scalar_one()
                assert insight.fusion_analysis['gentle_review']['body'].startswith('enc:v1:')
            if os.getenv('DIARY_REVIEW_ANDROID') == '1':
                adb = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'
                process = await asyncio.to_thread(run_instrumentation, [str(adb), '-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w',
                    '-e', 'class', 'com.example.mydiary.DiaryReviewInstrumentedTest#savedEditorReadsCacheAndBlocksDirtyReview',
                    '-e', 'review_qa_token', token, '-e', 'review_qa_user', str(uid), '-e', 'review_qa_diary', str(ids[0]),
                    'com.example.mydiary.test/androidx.test.runner.AndroidJUnitRunner'],
                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
                print(process.stdout[-4000:], flush=True)
                assert process.returncode == 0 and 'OK (1 test)' in process.stdout
            passed = True
        finally:
            # Cleanup remains possible if the local HTTP process restarts mid-test.
            async for db in get_db():
                for diary_id in ids:
                    await DiaryService(db).delete_diary(diary_id, uid)
                user = await db.get(User, uid)
                assert user.username == username and user.password == 'disabled-test-account'
                await db.delete(user)
                await db.commit()
            filename = 'diary-review-native.json' if os.getenv('DIARY_REVIEW_NATIVE_ONLY') == '1' else 'diary-review-live.json'
            output = ROOT.parent / 'docs/qa' / filename
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps({'passed': passed, 'samples': results, 'temporary_account_removed': True}, ensure_ascii=False, indent=2), encoding='utf-8')
            print('Disposable review account and all test diaries removed', flush=True)


if __name__ == '__main__':
    asyncio.run(main())
