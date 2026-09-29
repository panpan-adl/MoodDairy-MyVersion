"""
媒体路由

提供媒体文件上传相关的API端点
验证需求: 4.3, 5.5, 6.3
"""
from fastapi import APIRouter, Depends, HTTPException, status, UploadFile, File, Form
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError
import os
import hashlib
import logging

from app.database import get_db
from app.services.media_service import MediaService
from app.services.diary_service import DiaryService
from app.models.database import Diary, MediaUpload, MediaFile
from app.models.schemas import MediaUploadResponse, ErrorResponse
from app.utils.error_handler import (
    NotFoundError,
    ValidationError as AppValidationError,
    FileError,
    validate_file_size,
    validate_image_format,
    validate_audio_format,
    validate_video_format
)
from app.utils.media_utils import infer_media_type, get_supported_extensions
from app.config import settings
from app.security.deps import AuthenticatedUserId, ensure_user_match
from app.security.media_access import resolve_media_url

router = APIRouter(prefix="/media", tags=["media"])
logger = logging.getLogger(__name__)


async def _media_files_metadata_available(db: AsyncSession) -> bool:
    """Check whether media_files metadata can be used in the current database."""

    try:
        result = await db.execute(text("SELECT to_regclass('public.media_files')"))
        table_name = result.scalar()
        if not table_name:
            await db.rollback()
            return False

        await db.execute(text("SELECT 1 FROM public.media_files LIMIT 1"))
        await db.rollback()
        return True
    except Exception as exc:
        logger.warning("media_files metadata is unavailable: %s", exc)
        await db.rollback()
        return False


def _is_media_files_metadata_error(exc: Exception) -> bool:
    error_text = str(exc).lower()
    return "media_files" in error_text and any(
        marker in error_text
        for marker in ("does not exist", "undefinedtable", "permission denied", "insufficientprivilege")
    )


@router.post(
    "/upload",
    response_model=MediaUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="上传媒体文件",
    description="上传图片、音频或视频文件，支持 OSS 和本地存储，返回 asset_id（媒体资源ID）",
    responses={
        201: {"description": "文件上传成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误或文件格式不支持"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        413: {"model": ErrorResponse, "description": "文件大小超过限制"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def upload_media(
    current_user_id: AuthenticatedUserId,
    file: UploadFile = File(..., description="上传的媒体文件"),
    diary_id: int = Form(..., description="日记ID（用于验证权限）"),
    media_type: str = Form(..., description="媒体类型(image/audio/video，仅作参考)"),
    db: AsyncSession = Depends(get_db)
):
    """
    上传媒体文件（支持 OSS 和本地存储）
    
    重要特性：
    - 文件只读取一次到内存（避免重复读取）
    - 大文件（>50MB）使用流式上传，避免内存溢出
    - 媒体类型由服务端根据 content_type 和扩展名推断（不信任客户端）
    - 自动选择 OSS 或本地存储（基于配置）
    - OSS 不可用时自动回退到本地存储（如果启用回退）
    
    支持multipart/form-data格式上传
    返回 asset_id（MediaUpload.id 或 MediaFile.id）和文件URL
    
    注意：此接口只创建媒体资源记录，不创建 DiaryMedia 记录（日记内容块）。
    DiaryMedia 由 sync_media_items 接口创建，并通过 asset_id 关联。
    
    验证需求: 4.3, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 7.1, 7.3, 7.4, 7.6, 8.1
    
    Args:
        file: 上传的文件对象
        diary_id: 关联的日记ID（用于验证权限和获取 user_id）
        media_type: 客户端提供的媒体类型（仅作参考，服务端会重新推断）
        db: 数据库会话
        
    Returns:
        MediaUploadResponse: 包含 media_id (asset_id)、URL、类型、大小等信息
    """
    # 1. 获取文件元信息
    content_type = file.content_type or "application/octet-stream"
    filename = file.filename or "unknown"
    file_extension = os.path.splitext(filename)[1].lower()
    
    logger.info(f"开始上传文件: {filename}, content_type: {content_type}, diary_id: {diary_id}")
    
    # 2. 推断真实媒体类型（基于 content_type 和扩展名，不信任客户端）
    actual_media_type = infer_media_type(content_type, file_extension)
    if not actual_media_type:
        # 获取所有支持的格式
        supported_formats = {
            'image': list(get_supported_extensions('image')),
            'audio': list(get_supported_extensions('audio')),
            'video': list(get_supported_extensions('video'))
        }
        
        logger.warning(f"不支持的文件类型: content_type={content_type}, extension={file_extension}")
        raise HTTPException(
            status_code=400,
            detail={
                "message": f"不支持的文件类型: {content_type} {file_extension}",
                "error_code": "UNSUPPORTED_FILE_TYPE",
                "content_type": content_type,
                "file_extension": file_extension,
                "supported_formats": supported_formats
            }
        )
    
    logger.info(f"推断的媒体类型: {actual_media_type}")
    
    # 3. 验证文件大小（多层验证）
    # 第一层：检查 Content-Length 头（已在中间件处理）
    # 第二层：检查 FastAPI 提供的 file.size
    # 第三层：读取后验证实际大小
    max_size = 200 * 1024 * 1024  # 200MB
    content_length = file.size  # FastAPI 提供的大小（可能为 None）
    
    if content_length and content_length > max_size:
        logger.warning(f"文件大小超限（Content-Length）: {content_length / 1024 / 1024:.2f}MB > {max_size / 1024 / 1024}MB")
        raise HTTPException(
            status_code=413,
            detail={
                "message": f"文件大小 {content_length / 1024 / 1024:.2f}MB 超过限制（最大 {max_size / 1024 / 1024}MB）",
                "error_code": "FILE_TOO_LARGE",
                "max_size_mb": max_size / 1024 / 1024,
                "actual_size_mb": content_length / 1024 / 1024
            }
        )
    
    # 4. 验证日记是否存在并获取 user_id
    stmt = select(Diary).where(Diary.id == diary_id)
    result = await db.execute(stmt)
    diary = result.scalar_one_or_none()
    
    if not diary:
        logger.warning(f"日记不存在: diary_id={diary_id}")
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )

    ensure_user_match(current_user_id, diary.user_id)
    user_id = diary.user_id
    logger.info(f"验证通过: user_id={user_id}, diary_id={diary_id}")
    
    # 5. 根据文件大小选择处理方式
    large_file_threshold = 50 * 1024 * 1024  # 50MB
    use_streaming = content_length and content_length > large_file_threshold
    
    # 6. 上传文件（自动选择 OSS 或本地，自动选择流式或内存）
    media_service = MediaService()
    media_files_available = await _media_files_metadata_available(db)

    if media_service.oss_enabled and not media_files_available:
        if settings.oss_fallback_to_local:
            logger.warning("media_files metadata unavailable; forcing local storage fallback")
            media_service.oss_enabled = False
        else:
            raise HTTPException(
                status_code=503,
                detail={
                    "message": "media_files metadata is unavailable while OSS mode is enabled",
                    "error_code": "MEDIA_METADATA_UNAVAILABLE",
                },
            )
    
    try:
        if use_streaming:
            # 大文件：流式上传（不读入内存）
            logger.info(f"使用流式上传模式（文件大小: {content_length / 1024 / 1024:.2f}MB）")
            upload_result = await media_service.upload_media_streaming(
                file=file,
                media_type=actual_media_type,
                content_type=content_type,
                filename=filename
            )
            duration_ms = None  # 流式模式下暂不提取时长（避免二次读取）
        else:
            # 小文件：一次性读取到内存
            logger.info("使用内存上传模式")
            file_content = await file.read()
            file_size = len(file_content)
            
            # 第三层验证：验证实际读取的文件大小
            if file_size > max_size:
                logger.warning(f"实际文件大小超限: {file_size / 1024 / 1024:.2f}MB > {max_size / 1024 / 1024}MB")
                raise HTTPException(
                    status_code=413,
                    detail={
                        "message": f"文件大小 {file_size / 1024 / 1024:.2f}MB 超过限制（最大 {max_size / 1024 / 1024}MB）",
                        "error_code": "FILE_TOO_LARGE",
                        "max_size_mb": max_size / 1024 / 1024,
                        "actual_size_mb": file_size / 1024 / 1024
                    }
                )
            
            # 上传文件
            upload_result = await media_service.upload_media_bytes(
                file_bytes=file_content,
                media_type=actual_media_type,
                content_type=content_type,
                filename=filename
            )
            
            # 提取音频/视频时长（仅小文件，TODO: 实现时长提取）
            duration_ms = None
            # if actual_media_type in ['audio', 'video']:
            #     duration_ms = await extract_duration(file_content, actual_media_type)
        
        logger.info(f"文件上传成功: storage_mode={upload_result['storage_mode']}, url={upload_result['url']}")
        
    except HTTPException:
        # 重新抛出 HTTP 异常
        raise
    except Exception as e:
        logger.error(f"文件上传失败: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"文件上传失败: {str(e)}"
        )
    
    # 7. 写入数据库（带重试机制）
    max_retries = 3
    retry_delay = 0.5  # 500ms
    
    for attempt in range(max_retries):
        try:
            if upload_result['storage_mode'] == 'oss':
                # OSS 模式：写入 media_files 表
                logger.info(f"写入 media_files 表（尝试 {attempt + 1}/{max_retries}）")
                media_file = MediaFile(
                    diary_id=diary_id,
                    type=actual_media_type,  # 使用服务端推断的类型
                    url=upload_result['url'],
                    oss_bucket=upload_result.get('bucket'),
                    oss_key=upload_result.get('key'),
                    content_type=content_type,
                    size_bytes=upload_result['size_bytes'],
                    duration_ms=duration_ms
                )
                db.add(media_file)
                await db.commit()
                await db.refresh(media_file)
                media_id = media_file.id
                logger.info(f"media_files 记录创建成功: id={media_id}")
            else:
                # 本地模式：写入 media_uploads 表（保持现有逻辑）
                logger.info(f"写入 media_uploads 表（本地存储模式，尝试 {attempt + 1}/{max_retries}）")
                
                # 计算校验和（如果是小文件且已读取）
                checksum = None
                if not use_streaming and 'file_content' in locals():
                    checksum = hashlib.sha256(file_content).hexdigest()
                
                media_upload = MediaUpload(
                    user_id=user_id,
                    file_name=filename,
                    file_path=upload_result['file_path'],
                    file_type=actual_media_type,
                    file_size=upload_result['size_bytes'],
                    mime_type=content_type,
                    thumbnail_path=None,  # TODO: 生成缩略图
                    duration=duration_ms // 1000 if duration_ms else None,  # 转换为秒
                    checksum=checksum
                )
                db.add(media_upload)
                await db.commit()
                await db.refresh(media_upload)
                media_id = media_upload.id
                logger.info(f"media_uploads 记录创建成功: id={media_id}")
            
            # 成功，跳出重试循环
            break
            
        except Exception as e:
            # 数据库写入失败
            await db.rollback()

            if (
                upload_result['storage_mode'] == 'oss'
                and settings.oss_fallback_to_local
                and _is_media_files_metadata_error(e)
            ):
                logger.warning(
                    "media_files metadata write failed; falling back to media_uploads metadata: %s",
                    e,
                )
                upload_result['storage_mode'] = 'local'
                upload_result['file_path'] = upload_result['url']
                continue
            
            if attempt < max_retries - 1:
                # 还有重试机会
                logger.warning(f"数据库写入失败，{retry_delay}秒后重试 ({attempt + 1}/{max_retries}): {e}")
                import asyncio
                await asyncio.sleep(retry_delay)
            else:
                # 最后一次重试也失败
                logger.error(f"数据库写入失败（已重试 {max_retries} 次）: {e}", exc_info=True)
                
                # 记录孤立文件信息（用于后续清理）
                if upload_result['storage_mode'] == 'oss':
                    logger.error(f"孤立的 OSS 文件: bucket={upload_result.get('bucket')}, key={upload_result.get('key')}")
                else:
                    logger.error(f"孤立的本地文件: path={upload_result.get('file_path')}")
                
                raise HTTPException(
                    status_code=500,
                    detail={
                        "message": "元数据保存失败（已重试多次）",
                        "error_code": "DATABASE_WRITE_FAILED",
                        "details": str(e)
                    }
                )
    
    # 8. 返回响应
    return MediaUploadResponse(
        media_id=media_id,  # 这是 asset_id
        media_url=resolve_media_url(upload_result['url']) or upload_result['url'],
        thumbnail_url=None,  # TODO: 缩略图支持
        file_size=upload_result['size_bytes'],
        type=actual_media_type,  # 返回服务端推断的类型
        duration_ms=duration_ms,
        content_type=content_type,
        storage_mode=upload_result['storage_mode'],
        message="文件上传成功"
    )
