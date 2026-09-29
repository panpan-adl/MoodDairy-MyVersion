"""Database-backed pending plan store with TTL enforcement."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Dict, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.database import ChatAgentPlan


@dataclass
class PendingPlan:
    plan_id: str
    user_id: int
    conversation_id: str
    tool: str
    mode: str
    requires_confirmation: bool
    arguments: Dict[str, Any]
    request_message: str
    context_snapshot: str
    created_at: datetime
    expires_at: datetime
    status: str


class PlanStoreError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class PlanStore:
    def __init__(self, ttl_minutes: int = 30):
        self._ttl_minutes = max(1, ttl_minutes)

    @staticmethod
    def _now() -> datetime:
        return datetime.now()

    def _make_expiry(self) -> datetime:
        return self._now() + timedelta(minutes=self._ttl_minutes)

    @staticmethod
    def _to_pending_plan(row: ChatAgentPlan) -> PendingPlan:
        return PendingPlan(
            plan_id=row.plan_id,
            user_id=int(row.user_id),
            conversation_id=row.conversation_id,
            tool=row.tool,
            mode=row.mode,
            requires_confirmation=bool(row.requires_confirmation),
            arguments=dict(row.arguments or {}),
            request_message=row.request_message or "",
            context_snapshot=row.context_snapshot or "",
            created_at=row.created_at,
            expires_at=row.expires_at,
            status=row.status,
        )

    async def create_plan(
        self,
        db: AsyncSession,
        *,
        user_id: int,
        conversation_id: str,
        tool: str,
        mode: str,
        requires_confirmation: bool,
        arguments: Dict[str, Any],
        request_message: str,
        context_snapshot: str,
    ) -> PendingPlan:
        row = ChatAgentPlan(
            plan_id=f"plan_{uuid.uuid4().hex[:12]}",
            user_id=user_id,
            conversation_id=conversation_id,
            tool=tool,
            mode=mode,
            requires_confirmation=1 if requires_confirmation else 0,
            arguments=dict(arguments),
            request_message=request_message,
            context_snapshot=context_snapshot,
            status="pending",
            expires_at=self._make_expiry(),
        )
        db.add(row)
        await db.commit()
        await db.refresh(row)
        return self._to_pending_plan(row)

    async def get_for_confirmation(
        self,
        db: AsyncSession,
        *,
        plan_id: str,
        user_id: int,
        conversation_id: str,
    ) -> PendingPlan:
        result = await db.execute(select(ChatAgentPlan).where(ChatAgentPlan.plan_id == plan_id))
        row = result.scalar_one_or_none()
        if row is None:
            raise PlanStoreError("PLAN_NOT_FOUND", "plan not found")

        if int(row.user_id) != int(user_id) or row.conversation_id != conversation_id:
            raise PlanStoreError("PLAN_MISMATCH", "plan user or conversation mismatch")

        if row.status != "pending":
            if row.status == "expired":
                raise PlanStoreError("PLAN_EXPIRED", "plan expired")
            raise PlanStoreError("PLAN_NOT_PENDING", f"plan is {row.status}")

        now = self._now()
        if row.expires_at <= now:
            row.status = "expired"
            row.error_code = "PLAN_EXPIRED"
            row.error_message = "plan expired"
            await db.commit()
            raise PlanStoreError("PLAN_EXPIRED", "plan expired")

        return self._to_pending_plan(row)

    async def mark_cancelled(self, db: AsyncSession, plan_id: str) -> None:
        await self._update_status(db, plan_id, "cancelled", error_code=None, error_message=None)

    async def mark_executed(self, db: AsyncSession, plan_id: str) -> None:
        await self._update_status(db, plan_id, "executed", error_code=None, error_message=None, set_executed=True)

    async def mark_failed(
        self,
        db: AsyncSession,
        plan_id: str,
        *,
        error_code: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> None:
        await self._update_status(
            db,
            plan_id,
            "failed",
            error_code=error_code,
            error_message=error_message,
        )

    async def _update_status(
        self,
        db: AsyncSession,
        plan_id: str,
        status: str,
        *,
        error_code: Optional[str],
        error_message: Optional[str],
        set_executed: bool = False,
    ) -> None:
        result = await db.execute(select(ChatAgentPlan).where(ChatAgentPlan.plan_id == plan_id))
        row = result.scalar_one_or_none()
        if row is None:
            return

        row.status = status
        row.error_code = error_code
        row.error_message = error_message
        row.confirmed_at = self._now()
        if set_executed:
            row.executed_at = self._now()

        await db.commit()


_plan_store: PlanStore | None = None


def get_plan_store() -> PlanStore:
    global _plan_store
    if _plan_store is None:
        ttl_minutes = int(os.getenv("AGENT_PLAN_TTL_MINUTES", "30"))
        _plan_store = PlanStore(ttl_minutes=ttl_minutes)
    return _plan_store
