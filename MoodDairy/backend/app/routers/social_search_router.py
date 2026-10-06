"""Social search account router (BYOK).

每个用户绑定并使用自己的 TikHub API Key：
- GET  /social-search/status   查询绑定状态与自己 TikHub 账户的实时余额
- POST /social-search/bind-key 粘贴并验证绑定自己的 API Key
- POST /social-search/unbind   解绑/更换 Key
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.database.connection import get_db
from app.security.deps import AuthenticatedUserId
from app.services.social_search import key_service
from app.services.social_search.tikhub_provider import SocialSearchError

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/social-search", tags=["SocialSearch"])

REGISTER_URL = "https://user.tikhub.io/register"
BILLING_URL = "https://user.tikhub.io/dashboard/billing"
API_KEYS_URL = "https://user.tikhub.io/dashboard/api"


class BindKeyRequest(BaseModel):
    api_key: str


def _guide_urls() -> dict:
    return {
        "register_url": REGISTER_URL,
        "billing_url": BILLING_URL,
        "api_keys_url": API_KEYS_URL,
    }


@router.get("/status")
async def get_social_search_status(
    user_id: AuthenticatedUserId,
    db: Annotated[AsyncSession, Depends(get_db)],
) -> dict:
    """返回当前用户的密钥绑定状态与 TikHub 账户实时余额。"""
    try:
        status = await key_service.refresh_status(db, user_id)
        status.update(_guide_urls())
        return status
    except SocialSearchError as e:
        logger.warning("Query TikHub status failed for user %s: %s", user_id, e)
        return {
            "configured": True,
            "bound": True,
            "unlocked": False,
            "balance": 0.0,
            "free_credit": 0.0,
            "total": 0.0,
            "email": None,
            "key_status": None,
            "message": str(e),
            "error_code": e.code,
            **_guide_urls(),
        }
    except Exception as e:  # noqa: BLE001
        logger.warning("Query social search status failed: %s", e)
        return {
            "configured": True,
            "bound": False,
            "unlocked": False,
            "balance": 0.0,
            "free_credit": 0.0,
            "total": 0.0,
            "message": "状态查询失败，请稍后重试",
            **_guide_urls(),
        }


@router.post("/bind-key")
async def bind_social_search_key(
    body: BindKeyRequest,
    user_id: AuthenticatedUserId,
    db: Annotated[AsyncSession, Depends(get_db)],
) -> dict:
    """验证并绑定用户自己的 TikHub API Key。"""
    try:
        status = await key_service.bind_key(db, user_id, body.api_key)
        status.update(_guide_urls())
        return status
    except SocialSearchError as e:
        logger.info("Bind TikHub key failed for user %s: %s", user_id, e.code)
        return {
            "configured": True,
            "bound": False,
            "unlocked": False,
            "balance": 0.0,
            "free_credit": 0.0,
            "total": 0.0,
            "ok": False,
            "message": str(e),
            "error_code": e.code,
            **_guide_urls(),
        }


@router.post("/unbind")
async def unbind_social_search_key(
    user_id: AuthenticatedUserId,
    db: Annotated[AsyncSession, Depends(get_db)],
) -> dict:
    """解绑当前用户的 TikHub Key。"""
    await key_service.unbind_key(db, user_id)
    return {
        "configured": True,
        "bound": False,
        "unlocked": False,
        "balance": 0.0,
        "free_credit": 0.0,
        "total": 0.0,
        **_guide_urls(),
    }
