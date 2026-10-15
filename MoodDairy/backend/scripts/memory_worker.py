"""Run separately from the API: python scripts/memory_worker.py [--once]."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dotenv import load_dotenv
load_dotenv(Path(__file__).resolve().parents[1]/'.env')
import asyncio
import tempfile
import shutil
from datetime import timedelta
from sqlalchemy import select
from app.models.memory_skills import FutureLetter, LetterAsset, LetterJob
from app.services import memory_skills as s
from app.services.memory_renderer import render,join_timelines
from app.database.connection import get_db,close_db

async def work():
    async for db in get_db():
        # One worker globally; advisory lock is released if its process dies.
        if db.bind.dialect.name=='postgresql':
            from sqlalchemy import text
            if not (await db.execute(text('SELECT pg_try_advisory_xact_lock(71309415)'))).scalar(): return False
        job=(await db.execute(select(LetterJob).where(LetterJob.state.in_(['queued','running'])).order_by(LetterJob.updated_at).with_for_update(skip_locked=True))).scalars().first()
        if not job: return False
        obj=await s.letter(db,(await db.get(FutureLetter,job.letter_id)).user_id,job.letter_id,True)
        if job.version!=obj.version or obj.state!='rendering':
            job.state='failed'; job.error='内容版本已变更'; await db.commit(); return True
        job.state='running'; job.attempts+=1; job.updated_at=s.clock()
        # Hold the transaction/row lock for this bounded render. A process crash rolls
        # the claim back, so the persistent queued job can be retried on restart.
        try:
            assets=(await db.execute(select(LetterAsset).where(LetterAsset.letter_id==obj.id).order_by(LetterAsset.created_at,LetterAsset.id))).scalars().all()
            work_root=(s.ROOT/'work').resolve(); work_root.mkdir(parents=True,exist_ok=True)
            # The global worker lock makes leftovers exclusively ours. Never walk
            # outside the explicit private work directory or follow a symlink.
            for previous in work_root.glob('render-*'):
                if previous.is_dir() and not previous.is_symlink() and previous.resolve().parent==work_root:
                    shutil.rmtree(previous)
            with tempfile.TemporaryDirectory(prefix='render-',dir=work_root) as tmp:
                folder=Path(tmp); inputs=[]
                for a in assets:
                    path=folder/a.id; path.write_bytes(s.load(a.id)); inputs.append((a.kind,path))
                output=folder/'result.mp4'
                await asyncio.to_thread(render,s.unpack(obj.payload),inputs,output)
                if obj.parent_id:
                    original=await s.letter(db,obj.user_id,obj.parent_id)
                    if original.state!='opened': raise RuntimeError('原信已不可用')
                    previous=folder/'original.mp4'; previous.write_bytes(s.load(original.id))
                    dual=folder/'dual.mp4'; await asyncio.to_thread(join_timelines,previous,output,dual); output=dual
                await asyncio.to_thread(s.store,obj.id,output.read_bytes())
            obj.state='ready'; job.state='succeeded'; job.error=None
        except Exception as error:
            obj.state='failed'; job.state='failed'
            job.error=str(error)[:120] if isinstance(error,RuntimeError) else '生成失败，素材保留，可稍后重试'
        job.updated_at=s.clock(); await db.commit(); return True

async def main():
    await s.ensure_schema()
    try:
        while True:
            try: handled=await work()
            except Exception:
                handled=False; print('Worker temporarily unavailable; retrying',flush=True)
            if '--once' in sys.argv: return
            await asyncio.sleep(0.5 if handled else 3)
    finally: await close_db()

if __name__=='__main__': asyncio.run(main())
