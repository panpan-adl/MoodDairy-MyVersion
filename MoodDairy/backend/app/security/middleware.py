"""JWT authentication middleware."""

from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.security.jwt_auth import decode_access_token


def _is_public_path(path: str) -> bool:
    if path in {"/", "/health", "/docs", "/openapi.json", "/redoc"}:
        return True
    if path.startswith("/api/users/login") or path.startswith("/api/users/register"):
        return True
    # Local static media (OSS uses presigned URLs)
    if path.startswith("/media/") or path.startswith("/uploads/"):
        return True
    return False


class JWTAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.method == "OPTIONS":
            return await call_next(request)

        path = request.url.path
        if _is_public_path(path):
            return await call_next(request)

        auth_header = request.headers.get("Authorization", "")
        token = None
        if auth_header.lower().startswith("bearer "):
            token = auth_header[7:].strip()

        if not token:
            return JSONResponse(
                status_code=401,
                content={"detail": "未登录或令牌缺失"},
                headers={"WWW-Authenticate": "Bearer"},
            )

        payload = decode_access_token(token)
        if not payload or "sub" not in payload:
            return JSONResponse(
                status_code=401,
                content={"detail": "无效或已过期的访问令牌"},
                headers={"WWW-Authenticate": "Bearer"},
            )

        try:
            request.state.user_id = int(payload["sub"])
        except (TypeError, ValueError):
            return JSONResponse(status_code=401, content={"detail": "无效的用户令牌"})

        return await call_next(request)
