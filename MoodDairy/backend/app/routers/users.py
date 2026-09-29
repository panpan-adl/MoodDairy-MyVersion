"""
用户路由

提供用户相关的API端点
验证需求: 1.1, 2.1, 3.1, 4.2, 5.2, 5.3
"""
from fastapi import APIRouter, Depends, HTTPException, Request, status, UploadFile, File
from sqlalchemy.ext.asyncio import AsyncSession
import os
import uuid
from pathlib import Path

from app.database import get_db
from app.services.user_service import UserService
from app.models.schemas import (
    LoginRequest,
    RegisterRequest,
    UpdateUserRequest,
    UserResponse,
    AuthResponse,
    AvatarUploadResponse,
    ErrorResponse
)
from app.security.jwt_auth import create_access_token
from app.security.deps import get_current_user_id, ensure_user_match
from app.security.audit import write_audit_log, client_ip
from app.utils.error_handler import (
    NotFoundError,
    ValidationError as AppValidationError,
    log_error
)

router = APIRouter(prefix="/api/users", tags=["users"])


def _auth_response(user) -> AuthResponse:
    token = create_access_token(user.id, user.username)
    base = UserResponse.model_validate(user)
    return AuthResponse(**base.model_dump(), access_token=token)


@router.post(
    "/login",
    response_model=AuthResponse,
    status_code=status.HTTP_200_OK,
    summary="用户登录",
    description="使用用户名和密码进行身份验证，返回 JWT 访问令牌",
    responses={
        200: {"description": "登录成功"},
        401: {"model": ErrorResponse, "description": "认证失败"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def login(
    request: LoginRequest,
    http_request: Request,
    db: AsyncSession = Depends(get_db)
):
    service = UserService(db)
    user = await service.authenticate_user(request.username, request.password)

    if not user:
        await write_audit_log(
            db,
            user_id=None,
            action="login",
            resource_type="user",
            result="failure",
            ip_address=client_ip(http_request),
            detail=f"username={request.username}",
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户名或密码错误"
        )

    # 先构建响应：write_audit_log 会 commit，导致 ORM 对象过期
    response = _auth_response(user)
    await write_audit_log(
        db,
        user_id=user.id,
        action="login",
        resource_type="user",
        resource_id=str(user.id),
        ip_address=client_ip(http_request),
    )
    return response


@router.post(
    "/register",
    response_model=AuthResponse,
    status_code=status.HTTP_201_CREATED,
    summary="用户注册",
    description="创建新用户账号并返回 JWT 访问令牌",
    responses={
        201: {"description": "注册成功"},
        400: {"model": ErrorResponse, "description": "用户名已存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def register(
    request: RegisterRequest,
    http_request: Request,
    db: AsyncSession = Depends(get_db)
):
    service = UserService(db)
    user = await service.create_user(request)

    if not user:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="用户名已存在"
        )

    response = _auth_response(user)
    await write_audit_log(
        db,
        user_id=user.id,
        action="register",
        resource_type="user",
        resource_id=str(user.id),
        ip_address=client_ip(http_request),
    )
    return response


@router.get(
    "/{user_id}",
    response_model=UserResponse,
    summary="获取用户信息",
    description="根据用户ID获取用户详细信息",
    responses={
        200: {"description": "成功获取用户信息"},
        404: {"model": ErrorResponse, "description": "用户不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_user(
    user_id: int,
    current_user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db)
):
    ensure_user_match(current_user_id, user_id)
    service = UserService(db)
    user = await service.get_user(user_id)

    if not user:
        raise NotFoundError(
            f"未找到ID为 {user_id} 的用户",
            details={"user_id": user_id}
        )

    return UserResponse.model_validate(user)


@router.put(
    "/{user_id}",
    response_model=UserResponse,
    summary="更新用户信息",
    description="更新用户的个人资料",
    responses={
        200: {"description": "用户信息更新成功"},
        404: {"model": ErrorResponse, "description": "用户不存在"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def update_user(
    user_id: int,
    request: UpdateUserRequest,
    http_request: Request,
    current_user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db)
):
    ensure_user_match(current_user_id, user_id)
    service = UserService(db)
    user = await service.update_user(user_id, request)

    if not user:
        raise NotFoundError(
            f"未找到ID为 {user_id} 的用户",
            details={"user_id": user_id}
        )

    response = UserResponse.model_validate(user)
    await write_audit_log(
        db,
        user_id=current_user_id,
        action="update_profile",
        resource_type="user",
        resource_id=str(user_id),
        ip_address=client_ip(http_request),
    )
    return response


@router.post(
    "/{user_id}/avatar",
    response_model=AvatarUploadResponse,
    summary="上传用户头像",
    description="上传用户头像图片",
    responses={
        200: {"description": "头像上传成功"},
        404: {"model": ErrorResponse, "description": "用户不存在"},
        400: {"model": ErrorResponse, "description": "文件格式错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def upload_avatar(
    user_id: int,
    file: UploadFile = File(...),
    current_user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db)
):
    ensure_user_match(current_user_id, user_id)

    allowed_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.webp'}
    file_ext = os.path.splitext(file.filename)[1].lower()

    if file_ext not in allowed_extensions:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"不支持的文件格式，仅支持: {', '.join(allowed_extensions)}"
        )

    upload_dir = Path("uploads/avatars")
    upload_dir.mkdir(parents=True, exist_ok=True)

    unique_filename = f"{uuid.uuid4()}{file_ext}"
    file_path = upload_dir / unique_filename

    try:
        contents = await file.read()
        with open(file_path, "wb") as f:
            f.write(contents)
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="文件上传失败"
        )

    avatar_url = f"/media/avatars/{unique_filename}"

    service = UserService(db)
    user = await service.update_avatar(user_id, avatar_url)

    if not user:
        if file_path.exists():
            file_path.unlink()
        raise NotFoundError(
            f"未找到ID为 {user_id} 的用户",
            details={"user_id": user_id}
        )

    return AvatarUploadResponse(
        avatar_url=avatar_url,
        message="头像上传成功"
    )
