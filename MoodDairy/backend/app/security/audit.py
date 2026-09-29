"""Security audit logging."""

from __future__ import annotations

import logging
from typing import Any, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.database import SecurityAuditLog

logger = logging.getLogger(__name__)


async def write_audit_log(
    db: AsyncSession,
    *,
    user_id: Optional[int],
    action: str,
    resource_type: str,
    resource_id: Optional[str] = None,
    result: str = "success",
    ip_address: Optional[str] = None,
    detail: Optional[str] = None,
) -> None:
    try:
        entry = SecurityAuditLog(
            user_id=user_id,
            action=action,
            resource_type=resource_type,
            resource_id=resource_id,
            result=result,
            ip_address=ip_address,
            detail=detail,
        )
        db.add(entry)
        await db.commit()
    except Exception as exc:
        await db.rollback()
        logger.warning("Audit log write failed: %s", exc)


def client_ip(request) -> Optional[str]:
    if request is None:
        return None
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded:
        return forwarded.split(",")[0].strip()
    if request.client:
        return request.client.host
    return None
