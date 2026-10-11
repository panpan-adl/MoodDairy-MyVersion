"""Personal video imports require an explicit user action, never an AI tool call."""
import asyncio
from typing import Annotated, Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, SecretStr
from sqlalchemy.ext.asyncio import AsyncSession
from app.database.connection import get_db
from app.security.deps import AuthenticatedUserId
from app.services import personal_videos as service
from app.services.social_search import key_service
from app.services.social_search.tikhub_provider import SocialSearchError, TikHubProvider
from app.config import settings

router=APIRouter(prefix="/personal-videos",tags=["PersonalVideos"])
class ImportRequest(BaseModel):
    platform: Literal["douyin","bilibili"]
    account: str = Field(default="",max_length=2000)
    cookie: SecretStr = SecretStr("")
    folder_id: str = Field(default="",max_length=2000)
    cursor: str = Field(default="0",pattern=r"^\d{1,20}$")
    accept_provider_charge: bool = False

@router.post("/import")
async def import_videos(body:ImportRequest,user_id:AuthenticatedUserId,db:Annotated[AsyncSession,Depends(get_db)]):
    if not body.accept_provider_charge:
        raise HTTPException(400,"导入会调用 TikHub 接口，请先确认按接口计费")
    try:
        source = service.normalize_source(body.platform, body.account if body.platform == "douyin" else body.folder_id)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from None
    if body.platform=="douyin" and not body.cookie.get_secret_value().strip():
        raise HTTPException(400,"请填写本人的抖音网页登录信息")
    record=await key_service.get_record(db,user_id)
    key=await key_service.get_plain_key(record) if record else None
    if not key or key=="[decryption_failed]":
        raise HTTPException(400,"请先绑定自己的 TikHub 密钥")
    provider=TikHubProvider(key,settings.tikhub_base_url,settings.tikhub_timeout_seconds)
    try:
        return await service.import_page(provider,user_id,body.platform,source if body.platform == "douyin" else "",body.cookie.get_secret_value(),body.cursor,source if body.platform == "bilibili" else "")
    except SocialSearchError as exc:
        raise HTTPException(502,str(exc)) from None

@router.get("")
async def list_videos(user_id:AuthenticatedUserId):
    items=await asyncio.to_thread(service.list_items,user_id)
    return {"total":len(items),"douyin":sum(i["platform"]=="douyin" for i in items),"bilibili":sum(i["platform"]=="bilibili" for i in items)}

@router.delete("/{platform}")
async def delete_videos(platform:Literal["douyin","bilibili"],user_id:AuthenticatedUserId):
    await asyncio.to_thread(service.delete_items,user_id,platform)
    return {"ok":True}
