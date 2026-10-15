from datetime import date
from typing import Annotated, Literal
from uuid import UUID, uuid4
import asyncio
from fastapi import APIRouter, Depends, UploadFile, File, Form, Request, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.exc import IntegrityError
from app.database import get_db
from app.security.deps import AuthenticatedUserId
from app.models.memory_skills import FutureLetter, LetterAsset, LetterJob, RoomItem
from app.models.database import User
from app.security.jwt_auth import decode_access_token
from app.services import memory_skills as s

DB=Annotated[AsyncSession,Depends(get_db)]

async def require_memory_account(request:Request,user:AuthenticatedUserId,db:DB):
    account=(await db.execute(select(User).where(User.id==user))).scalar_one_or_none()
    auth=request.headers.get('Authorization','')
    payload=decode_access_token(auth[7:]) if auth.lower().startswith('bearer ') else None
    if account is None or account.status!=1 or (payload and payload.get('username')!=account.username):
        raise HTTPException(401,'登录状态已失效，请重新登录。手机草稿仍保留。',headers={'WWW-Authenticate':'Bearer'})

router=APIRouter(tags=['MemorySkills'],dependencies=[Depends(require_memory_account)])

class Draft(BaseModel):
    request_id: UUID
    title: str=Field(min_length=1,max_length=80)
    text: str=Field(default='',max_length=12000)
    open_date: date
    timezone: str='Asia/Shanghai'
    diary_ids: list[int]=Field(default_factory=list,max_length=5)
    media_ids: list[int]=Field(default_factory=list,max_length=13)
    segments: list[str]=Field(default_factory=list,max_length=12)
    music: bool=True
class UpdateDraft(BaseModel):
    version: int
    title: str=Field(min_length=1,max_length=80)
    text: str=Field(default='',max_length=12000)
    open_date: date
    timezone: str='Asia/Shanghai'
    segments: list[str]=Field(default_factory=list,max_length=12)
    music: bool=True
class Version(BaseModel): version: int
class RenderRequest(Version): regenerate: bool=False
class RoomUpdate(BaseModel):
    version: int
    theme: Literal['study','bedroom','cabin']
class Item(BaseModel):
    room_version: int
    slot: int=Field(ge=0,lt=12)
    kind: Literal['book','frame','recorder','keepsake','mailbox']
    source_type: Literal['diary','media','letter']
    source_id: str=Field(min_length=1,max_length=36)
    title: str=Field(default='',max_length=80)
    description: str=Field(default='',max_length=300)
    replace_id: str|None=None
class Suggest(BaseModel): diary_ids: list[int]=Field(min_length=1,max_length=5)

@router.get('/memory-materials')
async def materials(user:AuthenticatedUserId,db:DB): return await s.materials(db,user)

@router.get('/memory-materials/{id}/preview')
async def preview_source(id:int,request:Request,user:AuthenticatedUserId,db:DB):
    media=await s.source_media(db,user,id); data=await s.read_source(media)
    if media.media_type=='image': data,mime=await asyncio.to_thread(s.validate_media,data,'image')
    else: mime='audio/mp4'
    return private_response(data,mime,request)

@router.get('/future-letters')
async def letters(user:AuthenticatedUserId,db:DB):
    rows=(await db.execute(select(FutureLetter).where(FutureLetter.user_id==user).order_by(FutureLetter.created_at.desc()))).scalars().all()
    # List views never contain letter text, even for drafts or due letters.
    return [{k:v for k,v in s.view(r).items() if k!='content'} for r in rows]

@router.post('/future-letters')
async def create(body:Draft,user:AuthenticatedUserId,db:DB):
    if s.opening(body.open_date,body.timezone)<=s.clock(): raise HTTPException(422,'请选择未来的开启日期')
    try: return s.view(await s.create(db,user,body))
    except IntegrityError:
        await db.rollback()
        row=(await db.execute(select(FutureLetter).where(FutureLetter.user_id==user,FutureLetter.request_id==str(body.request_id)))).scalar_one_or_none()
        if row: return s.view(row)
        raise HTTPException(409,'创建冲突，请刷新后重试')

@router.get('/future-letters/{id}')
async def detail(id:UUID,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id)); result=s.view(obj)
    if s.readable(obj):
        assets=(await db.execute(select(LetterAsset).where(LetterAsset.letter_id==obj.id))).scalars().all()
        result['assets']=[dict(id=a.id,kind=a.kind) for a in assets]
    job=(await db.execute(select(LetterJob).where(LetterJob.letter_id==obj.id).order_by(LetterJob.updated_at.desc()))).scalars().first()
    if job: result['job']=dict(id=job.id,state=job.state,error=job.error)
    return result

@router.put('/future-letters/{id}')
async def update(id:UUID,body:UpdateDraft,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id),True); s.editable(obj,body.version)
    opens=s.opening(body.open_date,body.timezone)
    if not obj.parent_id and opens<=s.clock(): raise HTTPException(422,'请选择未来的开启日期')
    data=s.unpack(obj.payload); data.update(title=body.title,text=body.text,segments=body.segments,music=body.music)
    obj.payload=s.pack(data); obj.opens_at=opens; obj.timezone=body.timezone; obj.version+=1; obj.state='draft'
    s.erase(obj.id); await db.commit(); return s.view(obj)

@router.post('/future-letters/{id}/assets')
async def upload(id:UUID,user:AuthenticatedUserId,db:DB,version:int=Form(...),kind:Literal['image','audio']=Form(...),file:UploadFile=File(...),request_id:UUID|None=Form(None)):
    obj=await s.letter(db,user,str(id),True)
    if request_id:
        existing=await db.get(LetterAsset,str(request_id))
        if existing and existing.letter_id==obj.id:
            if not s.readable(obj): raise HTTPException(409,'信件已封存')
            return dict(id=existing.id,version=obj.version)
        if existing: raise HTTPException(409,'素材请求冲突，请重新选择')
    s.editable(obj,version)
    data=await file.read(25*1024*1024+1)
    a=await s.add_asset(db,obj,data,kind,str(request_id) if request_id else None); obj.version+=1; obj.state='draft'; s.erase(obj.id)
    await db.commit(); return dict(id=a.id,version=obj.version)

@router.delete('/future-letters/{id}/assets/{asset}')
async def remove_asset(id:UUID,asset:UUID,version:int,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id),True); s.editable(obj,version)
    a=(await db.execute(select(LetterAsset).where(LetterAsset.id==str(asset),LetterAsset.letter_id==obj.id))).scalar_one_or_none()
    if not a: raise HTTPException(404,'素材不存在')
    await db.delete(a); obj.version+=1; obj.state='draft'; s.erase(obj.id); await db.commit(); s.erase(str(asset)); return s.view(obj)

@router.post('/future-letters/{id}/outline')
async def outline(id:UUID,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id))
    if obj.state not in {'draft','ready','failed'}: raise HTTPException(409,'当前状态不能整理')
    return await s.outline(obj)

@router.post('/future-letters/{id}/render')
async def render(id:UUID,body:RenderRequest,user:AuthenticatedUserId,db:DB):
    j=await s.queue_render(db,user,str(id),body.version,body.regenerate); return dict(id=j.id,state=j.state)

@router.post('/future-letters/{id}/seal')
async def seal(id:UUID,body:Version,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id),True)
    if obj.state=='sealed': return s.view(obj)
    if obj.state!='ready' or obj.version!=body.version: raise HTTPException(409,'请先生成并预览最新视频')
    if obj.opens_at<=s.clock(): raise HTTPException(409,'开启日期已过，请修改日期后重新生成')
    if not (s.ROOT/(obj.id+'.bin')).is_file(): raise HTTPException(409,'视频成品暂不可用，请重新生成')
    obj.state='sealed'; obj.version+=1; await db.commit(); return s.view(obj)

@router.post('/future-letters/{id}/open')
async def open_letter(id:UUID,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id),True)
    if obj.state=='opened': return s.view(obj)
    if obj.state!='sealed' or s.clock()<obj.opens_at: raise HTTPException(403,'还未到开启时间')
    obj.state='opened'; obj.opened_at=s.clock(); obj.version+=1; await db.commit(); return s.view(obj)

@router.post('/future-letters/{id}/respond')
async def respond(id:UUID,body:Draft,user:AuthenticatedUserId,db:DB):
    try: return s.view(await s.create(db,user,body,str(id)))
    except IntegrityError:
        await db.rollback()
        row=(await db.execute(select(FutureLetter).where(FutureLetter.parent_id==str(id),FutureLetter.user_id==user))).scalar_one_or_none()
        if row: return s.view(row)
        raise HTTPException(409,'创建冲突，请刷新后重试')

def private_response(data,mime,request,download=False):
    headers={'Cache-Control':'no-store, private','Pragma':'no-cache','Accept-Ranges':'bytes','X-Content-Type-Options':'nosniff'}
    if download: headers['Content-Disposition']='attachment; filename="future-letter.mp4"'
    value=request.headers.get('range')
    if value:
        import re
        match=re.fullmatch(r'bytes=(\d*)-(\d*)',value)
        if not match or not any(match.groups()): return Response(status_code=416,headers={'Content-Range':f'bytes */{len(data)}'})
        a,b=match.groups(); start=int(a) if a else max(0,len(data)-int(b)); end=min(int(b) if a and b else len(data)-1,len(data)-1)
        if start>end or start>=len(data): return Response(status_code=416,headers={'Content-Range':f'bytes */{len(data)}'})
        headers['Content-Range']=f'bytes {start}-{end}/{len(data)}'
        return Response(data[start:end+1],status_code=206,media_type=mime,headers=headers)
    return Response(data,media_type=mime,headers=headers)

@router.get('/future-letters/{id}/play')
async def play(id:UUID,request:Request,user:AuthenticatedUserId,db:DB,download:bool=False):
    obj=await s.letter(db,user,str(id))
    if obj.state not in {'ready','opened'}: raise HTTPException(403,'视频尚未生成或尚未开启')
    data=await asyncio.to_thread(s.load,obj.id); return private_response(data,'video/mp4',request,download)

@router.get('/future-letters/{id}/assets/{asset}')
async def asset(id:UUID,asset:UUID,request:Request,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id))
    if not s.readable(obj): raise HTTPException(403,'信件已封存')
    a=(await db.execute(select(LetterAsset).where(LetterAsset.id==str(asset),LetterAsset.letter_id==obj.id))).scalar_one_or_none()
    if not a: raise HTTPException(404,'素材不存在')
    return private_response(await asyncio.to_thread(s.load,a.id),a.mime,request)

@router.delete('/future-letters/{id}')
async def delete(id:UUID,user:AuthenticatedUserId,db:DB):
    obj=await s.letter(db,user,str(id),True)
    if obj.state=='rendering': raise HTTPException(409,'视频处理中，请稍后删除')
    assets=(await db.execute(select(LetterAsset).where(LetterAsset.letter_id==obj.id))).scalars().all()
    jobs=(await db.execute(select(LetterJob).where(LetterJob.letter_id==obj.id))).scalars().all()
    for item in [*assets,*jobs]: await db.delete(item)
    await db.delete(obj); await db.commit()
    for key in [str(id),*(a.id for a in assets)]: s.erase(key)
    return {'deleted':True}

@router.get('/emotion-space')
async def get_room(user:AuthenticatedUserId,db:DB):
    r=await s.room(db,user); await db.commit()
    items=(await db.execute(select(RoomItem).where(RoomItem.user_id==user).order_by(RoomItem.slot))).scalars().all()
    return dict(theme=r.theme,version=r.version,items=[await s.item_view(db,i) for i in items])

@router.put('/emotion-space')
async def theme(body:RoomUpdate,user:AuthenticatedUserId,db:DB):
    r=await s.room(db,user,True)
    if r.version!=body.version: raise HTTPException(409,'空间已变更，请刷新')
    r.theme=body.theme; r.version+=1; await db.commit(); return dict(theme=r.theme,version=r.version)

@router.post('/emotion-space/suggest')
async def suggest(body:Suggest,user:AuthenticatedUserId,db:DB): return await s.suggest(db,user,body.diary_ids)

@router.put('/emotion-space/items')
async def place(body:Item,user:AuthenticatedUserId,db:DB):
    r=await s.room(db,user,True)
    if r.version!=body.room_version: raise HTTPException(409,'空间已变更，请刷新后重试')
    await s.validate_item(db,user,body)
    occupied=(await db.execute(select(RoomItem).where(RoomItem.user_id==user,RoomItem.slot==body.slot))).scalar_one_or_none()
    target=None
    if body.replace_id:
        target=(await db.execute(select(RoomItem).where(RoomItem.user_id==user,RoomItem.id==body.replace_id))).scalar_one_or_none()
        if not target: raise HTTPException(404,'待修改物品不存在')
    if occupied and (not target or occupied.id!=target.id): raise HTTPException(409,'位置已被占用，请明确选择替换该位置的物品')
    if not target: target=RoomItem(id=str(uuid4()),user_id=user); db.add(target)
    target.slot=body.slot; target.kind=body.kind; target.source_type=body.source_type; target.source_id=body.source_id
    target.payload=s.pack(dict(title=body.title,description=body.description)); target.version=(target.version or 0)+1
    r.version+=1; await db.commit(); return await s.item_view(db,target)

@router.delete('/emotion-space/items/{id}')
async def remove_item(id:UUID,version:int,user:AuthenticatedUserId,db:DB):
    r=await s.room(db,user,True)
    if r.version!=version: raise HTTPException(409,'空间已变更，请刷新')
    item=(await db.execute(select(RoomItem).where(RoomItem.id==str(id),RoomItem.user_id==user))).scalar_one_or_none()
    if not item: raise HTTPException(404,'物品不存在')
    await db.delete(item); r.version+=1; await db.commit(); return {'removed':True}

@router.get('/emotion-space/items/{id}/media')
async def room_media(id:UUID,request:Request,user:AuthenticatedUserId,db:DB):
    item=(await db.execute(select(RoomItem).where(RoomItem.id==str(id),RoomItem.user_id==user))).scalar_one_or_none()
    if not item or item.source_type!='media': raise HTTPException(404,'媒体不存在')
    media=await s.source_media(db,user,int(item.source_id)); data=await s.read_source(media)
    return private_response(data,'image/jpeg' if media.media_type=='image' else 'audio/mp4',request)
