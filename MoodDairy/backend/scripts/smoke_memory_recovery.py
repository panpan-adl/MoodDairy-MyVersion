"""Interrupt only our own test render, then verify supervisor/job recovery. Requires psutil."""
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT/'.env')
import asyncio
from datetime import date,timedelta
from uuid import uuid4
import psutil,httpx
from sqlalchemy import select
from app.database import get_db
from app.models.database import User
from app.models.memory_skills import LetterJob,FutureLetter
from app.security.jwt_auth import create_access_token

async def main():
    async for db in get_db():
        user=User(username='qa_recovery_'+uuid4().hex[:10],password='disabled-test-account',status=1)
        db.add(user); await db.commit(); uid=user.id
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000',headers={'Authorization':'Bearer '+create_access_token(uid,user.username)},timeout=40,trust_env=False) as c:
        id=None
        try:
            r=await c.post('/future-letters',json=dict(request_id=str(uuid4()),title='中断恢复测试',text='这是虚构的恢复测试记录。'*40,open_date=str(date.today()+timedelta(days=7)))); r.raise_for_status(); id=r.json()['id']
            r=await c.post(f'/future-letters/{id}/render',json={'version':1}); r.raise_for_status()
            for _ in range(100):
                candidates=[]
                for process in psutil.process_iter(['pid','name','cmdline']):
                    command=' '.join(process.info.get('cmdline') or [])
                    if 'python' in (process.info.get('name') or '').lower() and str(ROOT/'scripts/memory_worker.py') in command:
                        children=process.children(recursive=True)
                        if any('ffmpeg' in p.name().lower() for p in children): candidates.append((process,children))
                if candidates: break
                await asyncio.sleep(.2)
            else: raise RuntimeError('No active test renderer found')
            async for db in get_db():
                pending=(await db.execute(select(LetterJob).where(LetterJob.state.in_(['queued','running'])))).scalars().all()
                if any(job.letter_id!=id for job in pending): raise RuntimeError('Other render jobs present; interruption skipped')
            worker,children=candidates[0]; worker.suspend()
            for child in children:
                try: child.kill()
                except psutil.NoSuchProcess: pass
            worker.kill()
            print('Interrupted only the disposable test render; waiting for automatic restart',flush=True)
            for _ in range(150):
                r=await c.get(f'/future-letters/{id}'); r.raise_for_status(); state=r.json()['state']
                if state=='ready': break
                if state=='failed': raise RuntimeError('Recovered render failed')
                await asyncio.sleep(2)
            else: raise RuntimeError('Recovery timed out')
            video=await c.get(f'/future-letters/{id}/play'); video.raise_for_status(); assert len(video.content)>1000
            print('PASS process interruption, automatic restart, persistent job recovery and private playback',flush=True)
        finally:
            if id: await c.delete(f'/future-letters/{id}')
            async for db in get_db():
                row=await db.get(User,uid); await db.delete(row); await db.commit()
            print('Recovery test account removed',flush=True)

if __name__=='__main__': asyncio.run(main())
