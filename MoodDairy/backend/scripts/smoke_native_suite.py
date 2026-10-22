"""Run account-dependent device tests through the shared, restoring test entry."""
import asyncio
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
from uuid import uuid4
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT / '.env')
import httpx
from sqlalchemy import select
from app.database import get_db
from app.models.database import User, Diary
from app.models.memory_skills import EmotionRoom, RoomItem, FutureLetter
from app.security.jwt_auth import create_access_token
from app.security.field_crypto import encrypt_field
from app.services.memory_skills import pack
from android_instrumentation import run_connected_tests


async def cleanup_owned_account(uid, name):
    """Use the normal deletion services directly if the HTTP process is unavailable."""
    from uuid import UUID
    from app.routers.memory_skills import delete as delete_letter
    from app.services.diary_service import DiaryService
    async for db in get_db():
        user = await db.get(User, uid)
        if user is None:
            return
        assert user.username == name and user.password == 'disabled-device-test-account'
        letter_ids = (await db.execute(select(FutureLetter.id).where(FutureLetter.user_id == uid))).scalars().all()
        for letter_id in letter_ids:
            await delete_letter(UUID(letter_id), uid, db)
        diary_ids = (await db.execute(select(Diary.id).where(Diary.user_id == uid))).scalars().all()
        for diary_id in diary_ids:
            await DiaryService(db).delete_diary(diary_id, uid)
        await db.delete(user)
        await db.commit()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--all', action='store_true', help='Run the full device suite with the synthetic fixtures')
    parser.add_argument('--tests', help='Run specific classes or methods with the same fixtures')
    options = parser.parse_args()
    name = 'qa_device_' + uuid4().hex[:12]
    uid = did = None
    result = {'passed': False}
    async for db in get_db():
        user = User(username=name, password='disabled-device-test-account', status=1)
        db.add(user)
        await db.flush()
        uid = user.id
        diary = Diary(user_id=uid, title=encrypt_field('测试的记忆'),
            content=encrypt_field('仅用于手机端验收的虚构日记。'),
            diary_date=datetime.now(ZoneInfo('Asia/Shanghai')).date())
        db.add(diary)
        await db.flush()
        did = diary.id
        db.add(EmotionRoom(user_id=uid, theme='study', version=1))
        db.add(RoomItem(user_id=uid, slot=0, kind='book', source_type='diary', source_id=str(did),
            payload=pack({'title': '测试的记忆', 'description': '仅用于测试'})))
        await db.commit()
    owned_file = ROOT / 'logs/native-suite-owned-account.json'
    owned_file.write_text(json.dumps({'id': uid, 'username': name}), encoding='utf-8')
    token = create_access_token(uid, name)
    headers = {'Authorization': 'Bearer ' + token}
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000', headers=headers,
                                timeout=140, trust_env=False) as c:
        try:
            r = await c.get(f'/insights/diaries/{did}/review')
            r.raise_for_status()
            r = await c.post(f'/insights/diaries/{did}/review', json={'content_version': r.json()['content_version']})
            r.raise_for_status()
            assert r.json().get('keywords'), 'Synthetic diary must have a cached keyword envelope'
            tests = ','.join([
                'com.example.mydiary.FunctionalUiInstrumentedTest#allMainPagesAndQuestionnaireRoutesMountWithoutCrash',
                'com.example.mydiary.DiaryReviewInstrumentedTest#savedEditorReadsCacheAndBlocksDirtyReview',
                'com.example.mydiary.MemorySkillsInstrumentedTest#nativeCreateRenderAndRoomWithDisposableAccount',
                'com.example.mydiary.MemorySkillsInstrumentedTest#deletedAccountIsAnAuthFailureAndKeepsDraft'])
            if options.all:
                tests = ''
            if options.tests:
                tests = options.tests
            completed = await asyncio.to_thread(run_connected_tests, tests, {
                'qa_token': token, 'qa_user_id': str(uid), 'review_qa_token': token,
                'review_qa_user': str(uid), 'review_qa_diary': str(did),
                'memory_qa_token': token, 'memory_qa_user': str(uid),
                'memory_qa_deleted_token': create_access_token(922337200000000001, 'qa-deleted-account')},
                timeout=720)
            result['test_exit_code'] = completed.returncode
            result['passed'] = completed.returncode == 0
            assert result['passed'], 'Native account-dependent acceptance failed'
        finally:
            try:
                await cleanup_owned_account(uid, name)
                result['temporary_account_removed'] = True
                owned_file.unlink(missing_ok=True)
            finally:
                (ROOT / 'logs/native-suite-smoke.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
                print(json.dumps(result), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
