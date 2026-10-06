"""BYOK 密钥管理：每个用户绑定自己的 TikHub API Key。

- 密钥经 field_crypto 加密后存入 user_tikhub_keys 表
- 绑定时实时调用 TikHub get_user_info 验证密钥并同步余额
- 搜索执行时按 user_id 取密钥，费用从该用户自己的 TikHub 账户扣除
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, Optional, Tuple

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from app.database.connection import async_session_maker
from app.models.database import UserTikhubKey
from app.security.field_crypto import decrypt_field, encrypt_field
from app.services.social_search.client import (
    SocialSearchClient,
    drop_user_client,
    get_social_search_client_for_key,
)
from app.services.social_search.tikhub_provider import SocialSearchError

logger = logging.getLogger(__name__)

_CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS user_tikhub_keys (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    api_key_encrypted TEXT NOT NULL,
    tikhub_email VARCHAR(200),
    balance NUMERIC(10, 4) DEFAULT 0,
    free_credit NUMERIC(10, 4) DEFAULT 0,
    key_status VARCHAR(50),
    account_disabled SMALLINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP
)
"""


async def ensure_table() -> None:
    """启动时幂等建表（项目未使用迁移框架）。"""
    async with async_session_maker() as session:
        await session.execute(text(_CREATE_TABLE_SQL))
        await session.commit()


async def get_record(db: AsyncSession, user_id: int) -> Optional[UserTikhubKey]:
    result = await db.execute(
        select(UserTikhubKey).where(UserTikhubKey.user_id == user_id)
    )
    return result.scalar_one_or_none()


async def get_plain_key(record: UserTikhubKey) -> Optional[str]:
    return decrypt_field(record.api_key_encrypted)


async def build_status(record: Optional[UserTikhubKey], info: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    """统一的状态响应结构（供路由/工具使用）。"""
    if record is None or info is None:
        return {
            "configured": True,
            "bound": False,
            "unlocked": False,
            "balance": 0.0,
            "free_credit": 0.0,
            "total": 0.0,
            "email": None,
            "key_status": None,
        }
    balance = float(info.get("balance") or 0.0)
    free_credit = float(info.get("free_credit") or 0.0)
    return {
        "configured": True,
        "bound": True,
        # 实测：免费额度不能用于 App V2 搜索端点（402），只有付费余额 > 0 才算开通
        "unlocked": balance > 0 and not info.get("account_disabled", False),
        "balance": balance,
        "free_credit": free_credit,
        "total": round(balance + free_credit, 4),
        "email": info.get("email"),
        "key_status": str(info["key_status"]) if info.get("key_status") is not None else None,
    }


async def sync_record(record: UserTikhubKey, info: Dict[str, Any]) -> None:
    record.balance = float(info.get("balance") or 0.0)
    record.free_credit = float(info.get("free_credit") or 0.0)
    record.tikhub_email = info.get("email")
    record.key_status = str(info["key_status"]) if info.get("key_status") is not None else None
    record.account_disabled = 1 if info.get("account_disabled", False) else 0
    record.last_synced_at = datetime.now()


async def bind_key(db: AsyncSession, user_id: int, raw_api_key: str) -> Dict[str, Any]:
    """验证并绑定用户的 TikHub 密钥；验证失败抛 SocialSearchError。"""
    api_key = (raw_api_key or "").strip()
    if len(api_key) < 16:
        raise SocialSearchError("密钥格式不正确，请粘贴完整的 API 密钥", code="INVALID_KEY")

    # 同一密钥此前可能绑定过其他账户（换绑）：清理缓存
    old = await get_record(db, user_id)
    if old is not None:
        old_plain = await get_plain_key(old)
        if old_plain:
            drop_user_client(old_plain)

    client = get_social_search_client_for_key(api_key)
    info = await client.get_balance()  # 401 会抛 SocialSearchError(INVALID_KEY)

    record = old or UserTikhubKey(user_id=user_id, api_key_encrypted=encrypt_field(api_key))
    record.api_key_encrypted = encrypt_field(api_key)
    await sync_record(record, info)

    if old is None:
        db.add(record)
    await db.commit()
    await db.refresh(record)

    logger.info("TikHub key bound for user_id=%s email=%s", user_id, info.get("email"))
    return await build_status(record, info)


async def unbind_key(db: AsyncSession, user_id: int) -> None:
    record = await get_record(db, user_id)
    if record is None:
        return
    plain = await get_plain_key(record)
    if plain:
        drop_user_client(plain)
    await db.delete(record)
    await db.commit()


async def _cached_status(record: UserTikhubKey, message: str = "") -> Dict[str, Any]:
    """TikHub 暂时不可达时，用数据库里上次同步的信息构造状态（避免误显示为未绑定）。"""
    balance = float(record.balance or 0.0)
    free_credit = float(record.free_credit or 0.0)
    return {
        "configured": True,
        "bound": True,
        "unlocked": balance > 0 and not record.account_disabled,
        "balance": balance,
        "free_credit": free_credit,
        "total": round(balance + free_credit, 4),
        "email": record.tikhub_email,
        "key_status": record.key_status,
        "stale": True,
        "message": message or "暂时无法连接 TikHub，显示的是上次同步的余额",
    }


async def refresh_status(db: AsyncSession, user_id: int) -> Dict[str, Any]:
    """查询绑定密钥的实时余额并落库；未绑定返回 bound=false；网络失败返回缓存。"""
    record = await get_record(db, user_id)
    if record is None:
        return await build_status(None, None)

    plain = await get_plain_key(record)
    if not plain:
        return await build_status(None, None)

    try:
        info = await get_social_search_client_for_key(plain).get_balance()
    except SocialSearchError as e:
        logger.warning("TikHub status refresh failed for user %s: %s", user_id, e.code)
        return await _cached_status(record, "暂时无法连接 TikHub，显示的是上次同步的余额")
    except Exception as e:  # noqa: BLE001
        logger.warning("TikHub status refresh error for user %s: %s", user_id, e)
        return await _cached_status(record)

    await sync_record(record, info)
    await db.commit()
    return await build_status(record, info)


async def get_user_search_client(
    db: AsyncSession, user_id: int
) -> Tuple[Optional[SocialSearchClient], Optional[UserTikhubKey]]:
    """供搜索工具使用：返回 (client, record)；未绑定密钥返回 (None, None)。"""
    record = await get_record(db, user_id)
    if record is None:
        return None, None
    plain = await get_plain_key(record)
    if not plain:
        return None, None
    return get_social_search_client_for_key(plain), record
