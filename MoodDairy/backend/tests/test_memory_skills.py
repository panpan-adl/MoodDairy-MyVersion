"""Exercise the real HTTP state machine on an isolated database and private store."""
import io
from datetime import date,datetime,timedelta
from uuid import uuid4
import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from sqlalchemy.ext.asyncio import create_async_engine,async_sessionmaker
from app.models.database import User,Diary,DiaryMedia,MediaFile,MediaUpload
from app.models.memory_skills import TABLES,FutureLetter,LetterJob
from app.routers.memory_skills import router
from app.database import get_db
from app.security.deps import get_current_user_id
from app.services import memory_skills as service

@pytest_asyncio.fixture
async def env(tmp_path,monkeypatch):
    monkeypatch.setattr(service,'ROOT',tmp_path/'private')
    engine=create_async_engine('sqlite+aiosqlite://')
    async with engine.begin() as conn:
        await conn.run_sync(lambda c: TABLES[0].metadata.create_all(c,tables=[User.__table__,Diary.__table__,MediaFile.__table__,DiaryMedia.__table__,MediaUpload.__table__,*TABLES]))
    maker=async_sessionmaker(engine,expire_on_commit=False)
    async with maker() as db:
        db.add_all([User(id=1,username='one',password='x'),User(id=2,username='two',password='x'),Diary(id=10,user_id=1,title='最初的日记',content='我完成了准备。',diary_date=date.today())]); await db.commit()
    app=FastAPI(); app.include_router(router)
    async def get_session():
        async with maker() as db:
            try: yield db
            except Exception: await db.rollback(); raise
    owner=[1]
    app.dependency_overrides[get_db]=get_session
    app.dependency_overrides[get_current_user_id]=lambda:owner[0]
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client: yield client,maker,owner
    await engine.dispose()

def draft(**kw):
    value=dict(request_id=str(uuid4()),title='未来的我',text='愿你记得今天。',open_date=str(date.today()+timedelta(days=7)),diary_ids=[10]); value.update(kw); return value
async def create(c,**kw):
    r=await c.post('/future-letters',json=draft(**kw)); assert r.status_code==200,r.text; return r.json()
async def ready(maker,id):
    service.store(id,b'fake-private-video')
    async with maker() as db:
        obj=await db.get(FutureLetter,id); obj.state='ready'; await db.commit()

@pytest.mark.asyncio
async def test_seal_blocks_every_media_route_and_enforces_server_clock(env,monkeypatch):
    c,maker,owner=env; l=await create(c); id=l['id']; await ready(maker,id)
    before=await c.get(f'/future-letters/{id}/play',headers={'Range':'bytes=0-3'})
    assert before.status_code==206 and before.content==b'fake'
    sealed=await c.post(f'/future-letters/{id}/seal',json={'version':1}); assert sealed.status_code==200
    assert 'content' not in sealed.json()
    assert (await c.get(f'/future-letters/{id}/play?download=true')).status_code==403
    assert (await c.post(f'/future-letters/{id}/open')).status_code==403
    assert (await c.put(f'/future-letters/{id}',json={**draft(), 'version':2})).status_code==409
    # Due status alone never grants readable content; opening is an explicit action.
    monkeypatch.setattr(service,'clock',lambda:datetime.utcnow()+timedelta(days=10))
    detail=(await c.get(f'/future-letters/{id}')).json(); assert detail['due'] and 'content' not in detail
    assert (await c.get(f'/future-letters/{id}/play')).status_code==403
    assert (await c.post(f'/future-letters/{id}/open')).json()['state']=='opened'
    assert (await c.post(f'/future-letters/{id}/open')).json()['state']=='opened'
    assert (await c.get(f'/future-letters/{id}/play')).content==b'fake-private-video'

@pytest.mark.asyncio
async def test_user_isolation_and_snapshot_does_not_change(env):
    c,maker,owner=env; l=await create(c); owner[0]=2
    assert (await c.get(f"/future-letters/{l['id']}")).status_code==404
    assert (await c.get(f"/future-letters/{l['id']}/play")).status_code==404
    assert (await c.post('/future-letters',json=draft())).status_code==404
    owner[0]=1
    async with maker() as db:
        d=await db.get(Diary,10); d.content='后来改了'; await db.commit()
        obj=await db.get(FutureLetter,l['id']); assert '愿你' not in obj.payload
    assert (await c.get(f"/future-letters/{l['id']}")).json()['content']['diaries'][0]['text']=='我完成了准备。'

@pytest.mark.asyncio
async def test_retry_render_and_create_do_not_duplicate(env):
    c,maker,owner=env; body=draft(); a=(await c.post('/future-letters',json=body)).json(); b=(await c.post('/future-letters',json=body)).json(); assert a['id']==b['id']
    route=f"/future-letters/{a['id']}/render"; j=(await c.post(route,json={'version':1})).json(); again=(await c.post(route,json={'version':1})).json(); assert j['id']==again['id']
    assert (await c.put(f"/future-letters/{a['id']}",json={**body,'version':1})).status_code==409
    async with maker() as db:
        job=await db.get(LetterJob,j['id']); assert job.state=='queued'

@pytest.mark.asyncio
async def test_regenerate_updates_ready_video_once_but_never_rewrites_sealed_letter(env):
    c,maker,owner=env; l=await create(c); id=l['id']
    j=(await c.post(f'/future-letters/{id}/render',json={'version':1})).json()
    async with maker() as db:
        job=await db.get(LetterJob,j['id']); job.state='succeeded'; await db.commit()
    await ready(maker,id)
    regenerated=await c.post(f'/future-letters/{id}/render',json={'version':1,'regenerate':True})
    assert regenerated.status_code==200 and regenerated.json()['id']!=j['id']
    again=await c.post(f'/future-letters/{id}/render',json={'version':1,'regenerate':True})
    assert again.json()['id']==regenerated.json()['id']
    await ready(maker,id); await c.post(f'/future-letters/{id}/seal',json={'version':1})
    assert (await c.post(f'/future-letters/{id}/render',json={'version':2,'regenerate':True})).status_code==409

@pytest.mark.asyncio
async def test_room_conflicts_live_sources_and_removal_preserve_diary(env):
    c,maker,owner=env
    r=(await c.get('/emotion-space')).json(); body=dict(room_version=r['version'],slot=0,kind='book',source_type='diary',source_id='10',title='一段经历',description='我的记忆')
    a=(await c.put('/emotion-space/items',json=body)); assert a.status_code==200,a.text
    assert (await c.put('/emotion-space/items',json=body)).status_code==409
    room=(await c.get('/emotion-space')).json()
    body['room_version']=room['version']; assert (await c.put('/emotion-space/items',json=body)).status_code==409
    async with maker() as db:
        d=await db.get(Diary,10); d.content='新的内容'; await db.commit()
    assert (await c.get('/emotion-space')).json()['items'][0]['source']['text']=='新的内容'
    changed=await c.put('/emotion-space',json={'theme':'cabin','version':room['version']}); assert changed.status_code==200
    room=(await c.get('/emotion-space')).json(); assert len(room['items'])==1
    owner[0]=2; assert not (await c.get('/emotion-space')).json()['items']
    assert (await c.delete(f"/emotion-space/items/{a.json()['id']}?version=1")).status_code==404
    owner[0]=1
    assert (await c.delete(f"/emotion-space/items/{a.json()['id']}?version={room['version']}")).status_code==200
    async with maker() as db: assert await db.get(Diary,10)

@pytest.mark.asyncio
async def test_deleted_sources_and_sealed_room_do_not_leak_preview(env):
    c,maker,owner=env; l=await create(c); await ready(maker,l['id']); await c.post(f"/future-letters/{l['id']}/seal",json={'version':1})
    await c.get('/emotion-space')
    body=dict(room_version=1,slot=1,kind='mailbox',source_type='letter',source_id=l['id'],title='私人标题',description='敏感原文')
    result=await c.put('/emotion-space/items',json=body); assert result.status_code==200
    assert result.json()['description']=='' and 'content' not in result.json()['letter']
    await c.delete(f"/future-letters/{l['id']}")
    item=(await c.get('/emotion-space')).json()['items'][0]; assert item['missing'] and item['description']==''

@pytest.mark.asyncio
async def test_response_requires_open_and_is_idempotent(env,monkeypatch):
    c,maker,owner=env; l=await create(c); path=f"/future-letters/{l['id']}/respond"; body=draft()
    assert (await c.post(path,json=body)).status_code==409
    await ready(maker,l['id']); await c.post(f"/future-letters/{l['id']}/seal",json={'version':1})
    monkeypatch.setattr(service,'clock',lambda:datetime.utcnow()+timedelta(days=10)); await c.post(f"/future-letters/{l['id']}/open")
    a=(await c.post(path,json=body)).json(); b=(await c.post(path,json=draft())).json(); assert a['id']==b['id'] and a['parent_id']==l['id']

@pytest.mark.asyncio
async def test_assets_validation_limits_and_sealed_access(env):
    from PIL import Image
    c,maker,owner=env; l=await create(c); id=l['id']
    assert (await c.post(f'/future-letters/{id}/assets',data={'version':1,'kind':'image'},files={'file':('bad',b'not an image')})).status_code==422
    image=io.BytesIO(); Image.new('RGB',(10,10),'blue').save(image,'PNG')
    request_id=str(uuid4())
    a=await c.post(f'/future-letters/{id}/assets',data={'version':1,'kind':'image','request_id':request_id},files={'file':('photo.png',image.getvalue())}); assert a.status_code==200,a.text
    retry=await c.post(f'/future-letters/{id}/assets',data={'version':1,'kind':'image','request_id':request_id},files={'file':('photo.png',image.getvalue())})
    assert retry.status_code==200 and retry.json()==a.json()
    route=f"/future-letters/{id}/assets/{a.json()['id']}"
    assert (await c.get(route)).status_code==200
    await ready(maker,id); assert (await c.post(f'/future-letters/{id}/seal',json={'version':2})).status_code==200
    assert (await c.get(route)).status_code==403

def test_date_timezone_and_long_subtitles():
    from app.services.memory_renderer import captions,lines,font
    assert service.opening(date(2030,1,1),'Asia/Shanghai')==datetime(2029,12,31,16)
    assert len(lines('这是很长的中文，用来验证字幕换行。'*8,font(34),560))>1
    with pytest.raises(RuntimeError): captions({'text':'字'*2500})

def test_letter_paper_keeps_every_character_and_does_not_orphan_punctuation(tmp_path):
    from app.services.memory_renderer import paper_lines,font,letter_layout
    text='愿你有认真奔跑的勇气，也有停下来休息的自在。等到约定的那一天，再把故事讲给我听。'
    wrapped=paper_lines(text,font(42,letter=True),528)
    assert ''.join(wrapped)==text
    assert all(row[0] not in '，。！？；：、' for row in wrapped)
    payload={'title':'写给未来的自己','text':text+'\n\n'+text*4}
    layout=letter_layout(payload,[],tmp_path)
    assert layout['scroll_distance']>0 and 0<layout['duration']<=180
    from PIL import Image
    with Image.open(tmp_path/'letter-scroll.png') as image: assert image.height>layout['viewport_height']
    assert layout['text']==payload['text']

@pytest.mark.asyncio
async def test_missing_account_rejected_before_room_insert_and_other_memory_actions(env):
    from app.models.memory_skills import EmotionRoom
    c,maker,owner=env; owner[0]=24
    for method,path,body in [('GET','/emotion-space',None),('GET','/future-letters',None),('GET','/memory-materials',None),('POST','/future-letters',draft(diary_ids=[]))]:
        r=await c.request(method,path,json=body)
        assert r.status_code==401 and '重新登录' in r.json()['detail']
    async with maker() as db: assert await db.get(EmotionRoom,24) is None
    owner[0]=1
    assert (await c.get('/emotion-space')).status_code==200

@pytest.mark.asyncio
async def test_disabled_account_and_old_token_for_reused_id_are_rejected(env):
    from app.security.jwt_auth import create_access_token
    c,maker,owner=env
    async with maker() as db:
        user=await db.get(User,1); user.status=0; await db.commit()
    assert (await c.get('/emotion-space')).status_code==401
    async with maker() as db:
        user=await db.get(User,1); user.status=1; await db.commit()
    r=await c.get('/emotion-space',headers={'Authorization':'Bearer '+create_access_token(1,'previous-account')})
    assert r.status_code==401

@pytest.mark.asyncio
async def test_exact_deadline_and_invalid_ranges(env,monkeypatch):
    c,maker,owner=env; l=await create(c); id=l['id']; await ready(maker,id)
    assert (await c.get(f'/future-letters/{id}/play',headers={'Range':'bytes=9999-'})).status_code==416
    assert (await c.get(f'/future-letters/{id}/play',headers={'Range':'bytes=-5'})).content==b'video'
    await c.post(f'/future-letters/{id}/seal',json={'version':1})
    target=datetime.fromisoformat(l['opens_at'].replace('Z','+00:00')).replace(tzinfo=None)
    monkeypatch.setattr(service,'clock',lambda:target-timedelta(microseconds=1))
    assert (await c.post(f'/future-letters/{id}/open')).status_code==403
    monkeypatch.setattr(service,'clock',lambda:target)
    assert (await c.post(f'/future-letters/{id}/open')).status_code==200

@pytest.mark.asyncio
async def test_forged_media_reference_cannot_copy_another_users_asset(env):
    c,maker,owner=env
    async with maker() as db:
        db.add(Diary(id=20,user_id=2,title='其他账号',content='不应读取',diary_date=date.today())); await db.flush()
        db.add(MediaFile(id=50,diary_id=20,type='image',url='https://example.invalid/private.jpg')); await db.flush()
        db.add(DiaryMedia(id=30,diary_id=10,asset_id=50,media_type='image',media_url='https://example.invalid/private.jpg')); await db.commit()
    r=await c.post('/future-letters',json=draft(media_ids=[30])); assert r.status_code==422
    await c.get('/emotion-space')
    r=await c.put('/emotion-space/items',json=dict(room_version=1,slot=0,kind='frame',source_type='media',source_id='30')); assert r.status_code==422
    r=await c.put('/emotion-space/items',json=dict(room_version=1,slot=0,kind='book',source_type='diary',source_id='invalid')); assert r.status_code==422
