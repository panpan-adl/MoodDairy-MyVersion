"""FastAPI auth dependencies."""

from __future__ import annotations

from typing import Annotated, Optional

from fastapi import Depends, HTTPException, Query, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.security.jwt_auth import decode_access_token

_bearer = HTTPBearer(auto_error=False)


async def get_current_user_id(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> int:
    state_uid = getattr(request.state, "user_id", None)
    if state_uid is not None:
        return int(state_uid)

    token = None
    if credentials and credentials.scheme.lower() == "bearer":
        token = credentials.credentials
    if not token:
        auth_header = request.headers.get("Authorization", "")
        if auth_header.lower().startswith("bearer "):
            token = auth_header[7:].strip()

    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="未登录或令牌缺失",
            headers={"WWW-Authenticate": "Bearer"},
        )

    payload = decode_access_token(token)
    if not payload or "sub" not in payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效或已过期的访问令牌",
            headers={"WWW-Authenticate": "Bearer"},
        )

    try:
        return int(payload["sub"])
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效的用户令牌",
        ) from exc


def resolve_user_id(current_user_id: int, requested_user_id: Optional[int]) -> int:
    """Use JWT user id; reject mismatched client-supplied user_id (IDOR prevention)."""
    if requested_user_id is None:
        return current_user_id
    if requested_user_id != current_user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="无权访问其他用户的数据",
        )
    return current_user_id


def ensure_user_match(current_user_id: int, resource_user_id: int) -> None:
    if resource_user_id != current_user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="无权访问该资源",
        )


async def require_user_id(
    user_id: Optional[int] = Query(None, alias="user_id"),
    current_user_id: int = Depends(get_current_user_id),
) -> int:
    """Resolve authenticated user; optional query user_id must match token."""
    return resolve_user_id(current_user_id, user_id)


AuthenticatedUserId = Annotated[int, Depends(get_current_user_id)]
EffectiveUserId = Annotated[int, Depends(require_user_id)]
