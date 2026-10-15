"""Full acceptance with synthetic data only. Never reads a personal diary."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dotenv import load_dotenv
load_dotenv(Path(__file__).resolve().parents[1]/'.env')
import asyncio,io,time
from datetime import date,datetime,timedelta
from uuid import uuid4
import httpx
from PIL import Image
from sqlalchemy import select
from app.database import get_db
from app.models.database import User,Diary
from app.models.memory_skills import FutureLetter
from app.security.jwt_auth import create_access_token
from app.security.field_crypto import encrypt_field
from app.services.memory_renderer import audio_duration
from app.services import memory_skills as service

async def main():
    async for db in get_db():
        user=User(username='qa_memory_'+uuid4().hex[:10],password='disabled-smoke-account',status=1)
        db.add(user); await db.flush()
        diary=Diary(user_id=user.id,title=encrypt_field('测试中的一次小进步'),content=encrypt_field('今天我完成了汇报准备，想把这段经历留给未来。'),diary_date=date.today())
        db.add(diary); await db.commit(); uid=user.id; did=diary.id
    token=create_access_token(uid,user.username)
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8000',headers={'Authorization':'Bearer '+token},timeout=90,trust_env=False) as c:
        async def call(method,path,**kwargs):
            r=await c.request(method,path,**kwargs); r.raise_for_status(); return r
        ids=[]
        try:
            body=dict(request_id=str(uuid4()),title='给未来的我',text='愿你记得今天的努力。',open_date=str(date.today()+timedelta(days=7)),diary_ids=[did])
            letter=(await call('POST','/future-letters',json=body)).json(); id=letter['id']; ids.append(id)
            assert (await call('POST','/future-letters',json=body)).json()['id']==id
            image=io.BytesIO(); Image.new('RGB',(500,700),'#9AEDCF').save(image,'PNG')
            uploaded=(await call('POST',f'/future-letters/{id}/assets',data={'version':1,'kind':'image'},files={'file':('synthetic.png',image.getvalue())})).json()
            outline=(await call('POST',f'/future-letters/{id}/outline')).json(); assert outline['segments']
            print('PASS real cloud outline, synthetic sources only',flush=True)
            update={**body,'version':uploaded['version'],'segments':outline['segments']}
            letter=(await call('PUT',f'/future-letters/{id}',json=update)).json()
            await call('POST',f'/future-letters/{id}/render',json={'version':letter['version']})
            async def wait_video(id):
                for _ in range(150):
                    l=(await call('GET',f'/future-letters/{id}')).json()
                    if l['state']=='ready': return l
                    if l['state']=='failed': raise RuntimeError(str(l.get('job')))
                    await asyncio.sleep(2)
                raise RuntimeError('Render worker timeout')
            letter=await wait_video(id)
            video=(await call('GET',f'/future-letters/{id}/play')).content; assert len(video)>1000 and audio_duration(video)>0
            ranged=await call('GET',f'/future-letters/{id}/play',headers={'Range':'bytes=0-63'}); assert ranged.status_code==206
            print('PASS real private MP4, Chinese subtitles, music and range playback',flush=True)
            await call('POST',f'/future-letters/{id}/seal',json={'version':letter['version']})
            assert (await c.get(f'/future-letters/{id}/play')).status_code==403
            assert (await c.get(f'/future-letters/{id}/assets/{uploaded["id"]}')).status_code==403
            assert 'content' not in (await call('GET',f'/future-letters/{id}')).json()
            assert (await c.post(f'/future-letters/{id}/open')).status_code==403
            # Advance only this disposable test object's deadline, not the server clock.
            async for db in get_db():
                row=await db.get(FutureLetter,id); row.opens_at=datetime.utcnow()-timedelta(seconds=1); await db.commit()
            await call('POST',f'/future-letters/{id}/open')
            print('PASS seal protection and explicit due opening',flush=True)
            response_body={**body,'request_id':str(uuid4()),'title':'现在的回应','text':'我完成了新的尝试，也记得当时的心情。','diary_ids':[]}
            response=(await call('POST',f'/future-letters/{id}/respond',json=response_body)).json(); rid=response['id']; ids.append(rid)
            assert (await call('POST',f'/future-letters/{id}/respond',json=response_body)).json()['id']==rid
            await call('POST',f'/future-letters/{rid}/render',json={'version':response['version']})
            await wait_video(rid)
            dual=(await call('GET',f'/future-letters/{rid}/play')).content
            assert audio_duration(dual)>audio_duration(video)
            print('PASS real two-time-line response composition',flush=True)
            room=(await call('GET','/emotion-space')).json()
            suggestion=(await call('POST','/emotion-space/suggest',json={'diary_ids':[did]})).json(); assert suggestion['items']
            p=suggestion['items'][0]
            item=dict(room_version=room['version'],slot=p['slot'],kind=p['kind'],source_type='diary',source_id=str(did),title=p['title'],description=p['description'])
            await call('PUT','/emotion-space/items',json=item)
            room=(await call('GET','/emotion-space')).json()
            await call('PUT','/emotion-space/items',json=dict(room_version=room['version'],slot=(p['slot']+1)%12,kind='mailbox',source_type='letter',source_id=id,title='时光信',description=''))
            room=(await call('GET','/emotion-space')).json(); assert len(room['items'])==2
            await call('PUT','/emotion-space',json={'version':room['version'],'theme':'cabin'})
            print('PASS real AI placement, explicit save, letter link and theme switch',flush=True)
        finally:
            for id in reversed(ids):
                try: await c.delete(f'/future-letters/{id}')
                except Exception: pass
            async for db in get_db():
                user=await db.get(User,uid); await db.delete(user); await db.commit()
            print('Synthetic acceptance account removed',flush=True)

if __name__=='__main__': asyncio.run(main())
