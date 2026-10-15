"""Native UI acceptance with an ephemeral account. The real session is restored by the test."""
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT/'.env')
import asyncio,subprocess,os
from uuid import uuid4
from datetime import date
import httpx
from app.models.database import User,Diary
from app.models.memory_skills import EmotionRoom,RoomItem
from app.security.jwt_auth import create_access_token
from app.services import memory_skills as s
from app.database import get_db

async def main():
    async for db in get_db():
        user=User(username='qa_native_'+uuid4().hex[:10],password='disabled-test-account',status=1); db.add(user); await db.flush()
        diary=Diary(user_id=user.id,title=s.encrypt_field('测试的记忆'),content=s.encrypt_field('仅用于手机端验收的虚构日记。'),diary_date=date.today()); db.add(diary); await db.flush()
        db.add(EmotionRoom(user_id=user.id,theme='study',version=1)); db.add(RoomItem(user_id=user.id,slot=0,kind='book',source_type='diary',source_id=str(diary.id),payload=s.pack({'title':'测试的记忆','description':'仅用于测试'})))
        await db.commit(); uid=user.id
    token=create_access_token(uid,user.username)
    adb=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
    try:
        deleted=create_access_token(922337200000000001,'qa-deleted-account')
        tests='com.example.mydiary.MemorySkillsInstrumentedTest#nativeCreateRenderAndRoomWithDisposableAccount,com.example.mydiary.MemorySkillsInstrumentedTest#deletedAccountIsAnAuthFailureAndKeepsDraft'
        result=await asyncio.to_thread(subprocess.run,[str(adb),'-s','emulator-5554','shell','am','instrument','-w','-e','class',tests,'-e','memory_qa_token',token,'-e','memory_qa_user',str(uid),'-e','memory_qa_deleted_token',deleted,'com.example.mydiary.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
        print(result.stdout[-5000:]); print(result.stderr[-500:])
        if result.returncode or 'OK (2 tests)' not in result.stdout: raise RuntimeError('Native acceptance did not pass')
    finally:
        async with httpx.AsyncClient(base_url='http://127.0.0.1:8000',headers={'Authorization':'Bearer '+token},timeout=60,trust_env=False) as client:
            rows=(await client.get('/future-letters')).json()
            for row in rows: await client.delete('/future-letters/'+row['id'])
        async for db in get_db():
            row=await db.get(User,uid); await db.delete(row); await db.commit()
        print('Native acceptance account removed')

if __name__=='__main__': asyncio.run(main())
