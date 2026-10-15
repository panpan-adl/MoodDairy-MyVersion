"""Security audit logging."""

from __future__ import annotations

import logging
from typing import Any, Optional

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import MetaData, Table
from weakref import WeakKeyDictionary


logger = logging.getLogger(__name__)
_audit_tables = WeakKeyDictionary()


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
        engine = db.bind
        table = _audit_tables.get(engine)
        if table is None:
            connection = await db.connection()
            table = await connection.run_sync(
                lambda conn: Table("security_audit_logs", MetaData(), autoload_with=conn)
            )
            _audit_tables[engine] = table
        values = {
            "user_id": user_id, "action": action, "resource_type": resource_type,
            "resource_id": resource_id, "result": result,
            "ip_address": ip_address, "detail": detail,
        }
        if table.c.resource_id.type.python_type is int and resource_id is not None:
            values["resource_id"] = int(resource_id) if str(resource_id).isdigit() else None
        if "metadata" in table.c:
            values["metadata"] = {"result": result, "detail": detail}
        values = {key: value for key, value in values.items() if key in table.c}
        await db.execute(table.insert().values(**values))
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
