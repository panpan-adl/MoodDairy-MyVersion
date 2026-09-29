"""Shared error response and exception handling helpers."""

from __future__ import annotations

import logging
import traceback
from datetime import datetime
from typing import Any, Dict, Optional

from fastapi import HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy.exc import DBAPIError, IntegrityError, OperationalError, ProgrammingError

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    handlers=[logging.FileHandler("app.log"), logging.StreamHandler()],
)

logger = logging.getLogger(__name__)


class AppError(Exception):
    """Base application error."""

    def __init__(
        self,
        message: str,
        status_code: int = status.HTTP_500_INTERNAL_SERVER_ERROR,
        error_code: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ):
        self.message = message
        self.status_code = status_code
        self.error_code = error_code or f"ERR_{status_code}"
        self.details = details or {}
        super().__init__(message)


class AppDatabaseError(AppError):
    """Database availability error."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message=message,
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            error_code="DB_ERROR",
            details=details,
        )


DatabaseError = AppDatabaseError


class ValidationError(AppError):
    """Application-level validation error."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message=message,
            status_code=status.HTTP_400_BAD_REQUEST,
            error_code="VALIDATION_ERROR",
            details=details,
        )


class NotFoundError(AppError):
    """Resource not found error."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message=message,
            status_code=status.HTTP_404_NOT_FOUND,
            error_code="NOT_FOUND",
            details=details,
        )


class FileError(AppError):
    """File handling error."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message=message,
            status_code=status.HTTP_400_BAD_REQUEST,
            error_code="FILE_ERROR",
            details=details,
        )


class VoiceProcessingError(AppError):
    """Voice processing error."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message=message,
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            error_code="VOICE_PROCESSING_ERROR",
            details=details,
        )


def create_error_response(
    error: str,
    detail: Optional[str] = None,
    status_code: int = status.HTTP_500_INTERNAL_SERVER_ERROR,
    error_code: Optional[str] = None,
    request_id: Optional[str] = None,
    details: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build the common error response shape."""

    response: Dict[str, Any] = {
        "error": error,
        "detail": detail or error,
        "status_code": status_code,
        "error_code": error_code or f"ERR_{status_code}",
        "timestamp": datetime.now().isoformat(),
    }

    if request_id:
        response["request_id"] = request_id
    if details:
        response["details"] = details

    return response


def log_error(
    error: Exception,
    request: Optional[Request] = None,
    context: Optional[Dict[str, Any]] = None,
    message: Optional[str] = None,
) -> None:
    """Log structured exception information."""

    error_info: Dict[str, Any] = {
        "error_type": type(error).__name__,
        "error_message": str(error),
        "timestamp": datetime.now().isoformat(),
        "traceback": traceback.format_exc(),
    }

    if request:
        error_info.update(
            {
                "method": request.method,
                "url": str(request.url),
                "client_host": request.client.host if request.client else None,
            }
        )

    if context:
        error_info["context"] = context

    if message:
        error_info["custom_message"] = message

    logger.error("Error occurred: %s", error_info)


async def handle_exception(request: Request, exc: Exception) -> JSONResponse:
    """Map exceptions into API-friendly JSON responses."""

    log_error(exc, request)

    if isinstance(exc, AppError):
        return JSONResponse(
            status_code=exc.status_code,
            content=create_error_response(
                error=exc.message,
                detail=exc.message,
                status_code=exc.status_code,
                error_code=exc.error_code,
                details=exc.details,
            ),
        )

    if isinstance(exc, HTTPException):
        return JSONResponse(
            status_code=exc.status_code,
            content=create_error_response(
                error=str(exc.detail),
                detail=str(exc.detail),
                status_code=exc.status_code,
            ),
        )

    if isinstance(exc, IntegrityError):
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content=create_error_response(
                error="数据完整性错误",
                detail="违反数据库约束，可能是重复数据或外键约束导致。",
                status_code=status.HTTP_400_BAD_REQUEST,
                error_code="DB_INTEGRITY_ERROR",
            ),
        )

    if isinstance(exc, OperationalError):
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=create_error_response(
                error="数据库服务不可用",
                detail="无法连接数据库或数据库操作失败。",
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                error_code="DB_OPERATIONAL_ERROR",
            ),
        )

    if isinstance(exc, ProgrammingError):
        error_text = str(exc)
        lower_text = error_text.lower()
        is_permission_issue = (
            "insufficientprivilege" in lower_text
            or "permission denied" in lower_text
            or "权限不够" in error_text
            or "权限不足" in error_text
        )

        if is_permission_issue:
            return JSONResponse(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                content=create_error_response(
                    error="数据库权限不足",
                    detail="当前数据库用户缺少目标表或序列权限，请执行权限授予脚本后重试。",
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    error_code="DB_PERMISSION_DENIED",
                ),
            )

        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content=create_error_response(
                error="数据库语句执行失败",
                detail="数据库返回编程错误，请检查 SQL、字段映射或权限配置。",
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                error_code="DB_PROGRAMMING_ERROR",
            ),
        )

    if isinstance(exc, DBAPIError):
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=create_error_response(
                error="数据库请求失败",
                detail="数据库驱动调用失败，请检查数据库连接与权限配置。",
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                error_code="DB_API_ERROR",
            ),
        )

    if isinstance(exc, PydanticValidationError):
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content=create_error_response(
                error="请求数据验证失败",
                detail=str(exc),
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                error_code="VALIDATION_ERROR",
            ),
        )

    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=create_error_response(
            error="服务器内部错误",
            detail="发生了未预期的错误，请稍后重试。",
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            error_code="INTERNAL_ERROR",
        ),
    )


def validate_file_size(file_size: int, max_size_mb: int = 50) -> None:
    """Validate an uploaded file size."""

    max_size_bytes = max_size_mb * 1024 * 1024
    if file_size > max_size_bytes:
        raise FileError(
            f"文件过大，最大支持 {max_size_mb}MB",
            details={"file_size": file_size, "max_size": max_size_bytes},
        )


def validate_file_format(filename: str, allowed_formats: list[str]) -> None:
    """Validate an uploaded file extension."""

    file_ext = filename.lower().split(".")[-1] if "." in filename else ""
    file_ext_with_dot = f".{file_ext}"

    if file_ext_with_dot not in allowed_formats:
        raise FileError(
            f"不支持的文件格式: {file_ext}",
            details={"filename": filename, "supported_formats": allowed_formats},
        )


def validate_audio_format(filename: str) -> None:
    validate_file_format(filename, [".wav", ".mp3", ".m4a", ".ogg", ".flac"])


def validate_image_format(filename: str) -> None:
    validate_file_format(filename, [".jpg", ".jpeg", ".png", ".gif", ".webp"])


def validate_video_format(filename: str) -> None:
    validate_file_format(filename, [".mp4", ".mov", ".avi", ".mkv", ".webm"])
