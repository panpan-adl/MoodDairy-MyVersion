"""
日记路由

提供日记相关的API端点
验证需求: 2.1, 2.2, 2.4, 1.3
"""
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from datetime import date as date_type
from typing import List, Optional
import logging

from app.database import get_db
from app.services.diary_service import DiaryService
from app.models.schemas import (
    CreateDiaryRequest,
    UpdateDiaryRequest,
    DiaryResponse,
    DiaryDetailResponse,
    DatesWithDiaryResponse,
    ErrorResponse,
    SyncMediaRequest,
    MediaItemResponse,
    DiarySummaryResponse,
    DiarySummarySimple
)
from app.utils.error_handler import (
    NotFoundError,
    ValidationError as AppValidationError,
    log_error
)
from app.security.deps import AuthenticatedUserId, EffectiveUserId, ensure_user_match
from app.security.audit import write_audit_log, client_ip
from app.security.media_access import resolve_aggregated_media_item, resolve_media_item_response, to_diary_response

router = APIRouter(prefix="/diaries", tags=["diaries"], dependencies=[])
logger = logging.getLogger(__name__)


@router.post(
    "/",
    response_model=DiaryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建日记",
    description="创建新的日记记录",
    responses={
        201: {"description": "日记创建成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def create_diary(
    request: CreateDiaryRequest,
    http_request: Request,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    创建日记
    
    验证需求: 2.1, 2.4
    
    Args:
        request: 创建日记请求数据
        db: 数据库会话
        
    Returns:
        DiaryResponse: 创建的日记对象
    """
    request = request.model_copy(update={"user_id": current_user_id})
    service = DiaryService(db)
    diary = await service.create_diary(request)

    response = to_diary_response(diary)
    await write_audit_log(
        db,
        user_id=current_user_id,
        action="diary.create",
        resource_type="diary",
        resource_id=str(diary.id),
        ip_address=client_ip(http_request),
    )

    return response


@router.get(
    "/dates-with-diary",
    response_model=DatesWithDiaryResponse,
    summary="获取有日记的日期列表",
    description="获取指定月份中有日记的所有日期",
    responses={
        200: {"description": "成功获取日期列表"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_dates_with_diary(
    user_id: EffectiveUserId,
    year: int,
    month: int,
    db: AsyncSession = Depends(get_db)
):
    """
    获取有日记的日期列表
    
    验证需求: 1.3
    
    Args:
        user_id: 用户ID
        year: 年份
        month: 月份 (1-12)
        db: 数据库会话
        
    Returns:
        DatesWithDiaryResponse: 包含日期列表的响应
    """
    # 验证月份范围
    if not (1 <= month <= 12):
        raise AppValidationError(
            f"月份必须在1-12之间: {month}",
            details={"month": month, "valid_range": "1-12"}
        )
    
    service = DiaryService(db)
    dates = await service.get_dates_with_diary(user_id, year, month)
    
    return DatesWithDiaryResponse(
        dates=dates,
        year=year,
        month=month,
        count=len(dates)
    )


@router.get(
    "/highlights",
    response_model=List[DiaryResponse],
    summary="获取高光时刻日记列表",
    description="获取用户标记为高光时刻的日记列表",
    responses={
        200: {"description": "成功获取高光时刻日记列表"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_highlight_diaries(
    user_id: EffectiveUserId,
    limit: int = 20,
    offset: int = 0,
    db: AsyncSession = Depends(get_db)
):
    """
    获取高光时刻日记列表
    
    Args:
        user_id: 用户ID
        limit: 每页数量
        offset: 偏移量
        db: 数据库会话
        
    Returns:
        List[DiaryResponse]: 高光时刻日记列表
    """
    service = DiaryService(db)
    diaries = await service.get_highlight_diaries(user_id, limit, offset)
    return [to_diary_response(diary) for diary in diaries]


@router.get(
    "/little-joys",
    response_model=List[DiaryResponse],
    summary="获取小确幸日记列表",
    description="获取用户标记为小确幸的日记列表",
    responses={
        200: {"description": "成功获取小确幸日记列表"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_little_joy_diaries(
    user_id: EffectiveUserId,
    limit: int = 20,
    offset: int = 0,
    db: AsyncSession = Depends(get_db)
):
    """
    获取小确幸日记列表
    
    Args:
        user_id: 用户ID
        limit: 每页数量
        offset: 偏移量
        db: 数据库会话
        
    Returns:
        List[DiaryResponse]: 小确幸日记列表
    """
    service = DiaryService(db)
    diaries = await service.get_little_joy_diaries(user_id, limit, offset)
    return [to_diary_response(diary) for diary in diaries]


@router.get(
    "/{date}",
    response_model=DiaryResponse,
    summary="获取指定日期的日记",
    description="根据日期获取日记内容及其关联的媒体项",
    responses={
        200: {"description": "成功获取日记"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        400: {"model": ErrorResponse, "description": "日期格式错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_diary_by_date(
    date: str,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    获取指定日期的日记
    
    验证需求: 2.2, 2.4
    
    Args:
        date: 日期字符串，格式为YYYY-MM-DD
        user_id: 用户ID
        db: 数据库会话
        
    Returns:
        DiaryResponse: 日记对象
    """
    # 解析日期
    try:
        diary_date = date_type.fromisoformat(date)
    except ValueError:
        raise AppValidationError(
            f"日期格式错误，应为YYYY-MM-DD格式: {date}",
            details={"date": date, "expected_format": "YYYY-MM-DD"}
        )
    
    service = DiaryService(db)
    diary = await service.get_diary_by_date(user_id, diary_date)
    
    if not diary:
        raise NotFoundError(
            f"未找到用户 {user_id} 在 {date} 的日记",
            details={"user_id": user_id, "date": date}
        )
    
    # 转换为响应模型
    return to_diary_response(diary)


@router.get(
    "/{date}/all",
    response_model=List[DiaryResponse],
    summary="获取指定日期的所有日记",
    description="根据日期获取该日期的所有日记，按创建时间降序排列",
    responses={
        200: {"description": "成功获取日记列表"},
        400: {"model": ErrorResponse, "description": "日期格式错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_all_diaries_by_date(
    date: str,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    获取指定日期的所有日记
    
    验证需求: 2.2, 2.4
    
    Args:
        date: 日期字符串，格式为YYYY-MM-DD
        user_id: 用户ID
        db: 数据库会话
        
    Returns:
        List[DiaryResponse]: 日记列表
    """
    # 解析日期
    try:
        diary_date = date_type.fromisoformat(date)
    except ValueError:
        raise AppValidationError(
            f"日期格式错误，应为YYYY-MM-DD格式: {date}",
            details={"date": date, "expected_format": "YYYY-MM-DD"}
        )
    
    service = DiaryService(db)
    diaries = await service.get_diaries_by_date(user_id, diary_date)
    
    # 转换为响应模型列表
    return [to_diary_response(diary) for diary in diaries]


@router.get(
    "/id/{diary_id}",
    response_model=DiaryResponse,
    summary="通过ID获取日记",
    description="根据日记ID获取日记内容及其关联的媒体项",
    responses={
        200: {"description": "成功获取日记"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_diary_by_id(
    diary_id: int,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    通过ID获取日记
    
    验证需求: 2.2, 2.4
    
    Args:
        diary_id: 日记ID
        db: 数据库会话
        
    Returns:
        DiaryResponse: 日记对象
    """
    service = DiaryService(db)
    diary = await service.get_diary_by_id(diary_id)
    
    if not diary:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )
    
    ensure_user_match(current_user_id, diary.user_id)
    
    # 转换为响应模型
    return to_diary_response(diary)


@router.get(
    "/id/{diary_id}/detail",
    response_model=DiaryDetailResponse,
    summary="通过ID获取日记详情（聚合媒体）",
    description="根据日记ID获取日记内容及其聚合的媒体项（OSS + 本地存储）",
    responses={
        200: {"description": "成功获取日记详情"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_diary_detail(
    diary_id: int,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    通过ID获取日记详情（包含聚合的媒体列表）
    
    聚合 media_files（OSS）和 media_uploads（本地）两张表的数据，
    返回统一格式的媒体列表。
    
    验证需求: 6.5
    
    Args:
        diary_id: 日记ID
        db: 数据库会话
        
    Returns:
        DiaryDetailResponse: 日记详情对象（包含聚合的媒体列表）
    """
    service = DiaryService(db)
    
    # 1. 查询日记基本信息
    diary = await service.get_diary_by_id(diary_id, load_media=False)
    
    if not diary:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )

    ensure_user_match(current_user_id, diary.user_id)
    
    # 2. 获取聚合的媒体列表
    media_items_aggregated = await service.get_diary_media_items(diary_id)
    media_items_aggregated = [
        resolve_aggregated_media_item(item) for item in media_items_aggregated
    ]
    
    # 3. 构建响应
    from app.models.schemas import DiaryDetailResponse
    
    return DiaryDetailResponse(
        id=diary.id,
        user_id=diary.user_id,
        title=diary.title,
        content=diary.content,
        diary_date=diary.diary_date,
        weather=diary.weather,
        location=diary.location,
        mood_score=diary.mood_score,
        mood_type=diary.mood_type,
        is_private=diary.is_private,
        is_extracted=diary.is_extracted,
        is_highlight=diary.is_highlight,
        is_little_joy=diary.is_little_joy,
        word_count=diary.word_count,
        extraction_status=diary.extraction_status,
        content_hash=diary.content_hash,
        extract_version=diary.extract_version,
        embedding_status=diary.embedding_status,
        created_at=diary.created_at,
        updated_at=diary.updated_at,
        media_items_aggregated=media_items_aggregated
    )


@router.put(
    "/{diary_id}",
    response_model=DiaryResponse,
    summary="更新日记",
    description="更新指定ID的日记内容",
    responses={
        200: {"description": "日记更新成功"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def update_diary(
    diary_id: int,
    request: UpdateDiaryRequest,
    http_request: Request,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    更新日记
    
    验证需求: 2.2, 2.4
    
    Args:
        diary_id: 日记ID
        request: 更新日记请求数据
        db: 数据库会话
        
    Returns:
        DiaryResponse: 更新后的日记对象
    """
    service = DiaryService(db)
    existing = await service.get_diary_by_id(diary_id, load_media=False)
    if not existing:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )
    ensure_user_match(current_user_id, existing.user_id)

    diary = await service.update_diary(diary_id, request)
    
    if not diary:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )

    response = to_diary_response(diary)
    await write_audit_log(
        db,
        user_id=current_user_id,
        action="diary.update",
        resource_type="diary",
        resource_id=str(diary_id),
        ip_address=client_ip(http_request),
    )

    return response



@router.delete(
    "/{diary_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除日记",
    description="删除指定ID的日记及其关联的媒体项和语音转录",
    responses={
        204: {"description": "日记删除成功"},
        404: {"model": ErrorResponse, "description": "日记不存在或不属于该用户"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def delete_diary(
    diary_id: int,
    http_request: Request,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    删除日记
    
    级联删除关联的媒体项和语音转录记录
    
    验证需求: 用户需求Priority 2 - 日记删除功能
    
    Args:
        diary_id: 日记ID
        user_id: 用户ID（用于权限验证）
        db: 数据库会话
        
    Returns:
        None: 成功删除返回204 No Content
    """
    service = DiaryService(db)
    success = await service.delete_diary(diary_id, user_id)
    
    if not success:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记或该日记不属于用户 {user_id}",
            details={"diary_id": diary_id, "user_id": user_id}
        )

    await write_audit_log(
        db,
        user_id=user_id,
        action="diary.delete",
        resource_type="diary",
        resource_id=str(diary_id),
        ip_address=client_ip(http_request),
    )
    
    # 返回 204 No Content（FastAPI 会自动处理）
    return None


@router.put(
    "/{diary_id}/media",
    response_model=List[MediaItemResponse],
    summary="批量同步媒体项",
    description="全量同步日记的媒体项（增删改），返回最新的媒体项列表",
    responses={
        200: {"description": "媒体项同步成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误（sort_order重复或id不属于该日记）"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def sync_diary_media(
    diary_id: int,
    request: SyncMediaRequest,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    批量同步媒体项（全量同步）
    
    逻辑：
    - 有 id 且属于该 diary → UPDATE
    - 有 id 但不属于该 diary → 400 错误
    - id = null → INSERT
    - DB 有但请求没有 → DELETE
    
    Args:
        diary_id: 日记ID
        request: 同步媒体请求（包含完整的媒体项列表）
        db: 数据库会话
        
    Returns:
        List[MediaItemResponse]: 同步后的媒体项列表（按 sort_order 排序）
    """
    service = DiaryService(db)
    
    # 先检查日记是否存在
    diary = await service.get_diary_by_id(diary_id, load_media=False)
    if not diary:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记",
            details={"diary_id": diary_id}
        )

    ensure_user_match(current_user_id, diary.user_id)
    
    try:
        # 执行同步
        media_items = await service.sync_media_items(diary_id, request.items)
        
        # 转换为响应模型
        return [resolve_media_item_response(MediaItemResponse.model_validate(item)) for item in media_items]
    except ValueError as e:
        raise AppValidationError(
            str(e),
            details={"diary_id": diary_id}
        )


# ============================================================================
# 日记提取相关接口 (Diary Extraction Endpoints)
# ============================================================================

@router.post(
    "/{diary_id}/extract",
    summary="触发日记提取",
    description="手动触发日记结构化提取任务",
    responses={
        200: {"description": "提取任务已创建"},
        404: {"model": ErrorResponse, "description": "日记不存在或不属于该用户"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def extract_diary(
    diary_id: int,
    user_id: EffectiveUserId,
    force: bool = False,
    db: AsyncSession = Depends(get_db)
):
    """
    触发日记提取
    
    手动触发日记结构化提取任务（异步执行）
    
    验证需求: 5.1, 5.2
    
    Args:
        diary_id: 日记ID
        user_id: 用户ID（用于权限验证）
        force: 是否强制重新提取（默认False）
        db: 数据库会话
        
    Returns:
        {
            "message": "提取任务已创建",
            "diary_id": 123,
            "status": "pending"
        }
        
    Security:
        - 只能提取自己的日记
    """
    # 验证日记所有权
    service = DiaryService(db)
    diary = await service.get_diary_by_id(diary_id, load_media=False)
    
    if not diary or diary.user_id != user_id:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记或该日记不属于用户 {user_id}",
            details={"diary_id": diary_id, "user_id": user_id}
        )
    
    # 触发异步提取任务
    from app.celery_app import extraction_queue_available, trigger_extraction

    if not extraction_queue_available():
        logger.warning(
            "Extraction queue unavailable; skipping manual extraction trigger: diary_id=%s user_id=%s force=%s",
            diary_id,
            user_id,
            force,
        )
        return {
            "message": "Extraction queue unavailable; request skipped",
            "diary_id": diary_id,
            "status": "skipped"
        }

    try:
        trigger_extraction(diary_id, force)
        
        logger.info(f"触发提取任务: diary_id={diary_id}, user_id={user_id}, force={force}")
        
        return {
            "message": "提取任务已创建",
            "diary_id": diary_id,
            "status": "pending"
        }
    except Exception as e:
        log_error(
            error=e,
            context={
                "diary_id": diary_id,
                "user_id": user_id,
                "force": force
            },
            message="触发提取任务失败"
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"触发提取任务失败: {str(e)}"
        )


@router.get(
    "/{diary_id}/extract/status",
    summary="查询提取任务状态",
    description="查询日记提取任务的执行状态",
    responses={
        200: {"description": "成功获取任务状态"},
        404: {"model": ErrorResponse, "description": "日记不存在或不属于该用户"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_extraction_status(
    diary_id: int,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    查询提取任务状态
    
    查询最新的提取任务记录，返回详细状态信息
    
    验证需求: 5.3
    
    Args:
        diary_id: 日记ID
        user_id: 用户ID（用于权限验证）
        db: 数据库会话
        
    Returns:
        {
            "diary_id": 123,
            "status": "succeeded",
            "attempts": 1,
            "started_at": "2026-01-17T10:00:00",
            "completed_at": "2026-01-17T10:00:05",
            "execution_time_ms": 5000,
            "error_message": null
        }
        
    Security:
        - 只能查询自己的日记
    """
    from app.models.database import Diary, ExtractionJob
    from sqlalchemy import select
    
    # 验证日记所有权
    stmt = select(Diary).where(Diary.id == diary_id)
    result = await db.execute(stmt)
    diary = result.scalar_one_or_none()
    
    if not diary or diary.user_id != user_id:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记或该日记不属于用户 {user_id}",
            details={"diary_id": diary_id, "user_id": user_id}
        )
    
    # 查询最新的提取任务
    stmt = (
        select(ExtractionJob)
        .where(ExtractionJob.diary_id == diary_id)
        .order_by(ExtractionJob.created_at.desc())
        .limit(1)
    )
    result = await db.execute(stmt)
    job = result.scalar_one_or_none()
    
    if not job:
        return {
            "diary_id": diary_id,
            "status": diary.extraction_status,
            "message": "未找到提取任务记录"
        }
    
    return {
        "diary_id": diary_id,
        "status": job.status,
        "attempts": job.attempts,
        "started_at": job.started_at.isoformat() if job.started_at else None,
        "completed_at": job.completed_at.isoformat() if job.completed_at else None,
        "execution_time_ms": job.execution_time_ms,
        "error_message": job.error_message,
        "error_code": job.error_code
    }


@router.get(
    "/{diary_id}/summary",
    response_model=DiarySummaryResponse,
    summary="获取单个日记摘要",
    description="获取指定日记的完整摘要信息",
    responses={
        200: {"description": "成功获取摘要"},
        404: {"model": ErrorResponse, "description": "日记不存在、不属于该用户或摘要不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_diary_summary(
    diary_id: int,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db)
):
    """
    获取单个日记的摘要
    
    返回完整的摘要信息（DiarySummaryResponse）
    
    验证需求: 3.1, 5.1
    
    Args:
        diary_id: 日记ID
        user_id: 用户ID（用于权限验证）
        db: 数据库会话
        
    Returns:
        DiarySummaryResponse: 完整的摘要信息
        
    Security:
        - 只能查询自己的日记
    """
    from app.models.database import Diary, DiarySummary
    from sqlalchemy import select
    
    # 验证日记所有权
    stmt = select(Diary).where(Diary.id == diary_id)
    result = await db.execute(stmt)
    diary = result.scalar_one_or_none()
    
    if not diary or diary.user_id != user_id:
        raise NotFoundError(
            f"未找到ID为 {diary_id} 的日记或该日记不属于用户 {user_id}",
            details={"diary_id": diary_id, "user_id": user_id}
        )
    
    # 查询摘要
    stmt = (
        select(DiarySummary, Diary.diary_date)
        .join(Diary, DiarySummary.diary_id == Diary.id)
        .where(DiarySummary.diary_id == diary_id)
    )
    result = await db.execute(stmt)
    row = result.first()
    
    if not row:
        raise NotFoundError(
            f"日记 {diary_id} 的摘要不存在，请先提取",
            details={"diary_id": diary_id}
        )
    
    summary, diary_date = row
    
    # 构建响应
    return DiarySummaryResponse(
        diary_id=summary.diary_id,
        diary_date=diary_date,
        summary=summary.summary,
        keywords=summary.keywords,
        main_topics=summary.main_topics,
        people_mentioned=summary.people_mentioned,
        places_mentioned=summary.places_mentioned,
        primary_emotion=summary.primary_emotion,
        emotion_score=summary.emotion_score,
        emotion_intensity=summary.emotion_intensity,
        emotion_distribution=summary.emotion_distribution,
        has_small_happiness=summary.has_small_happiness,
        small_happiness_content=summary.small_happiness_content,
        has_highlight=summary.has_highlight,
        highlight_summary=summary.highlight_summary,
        extract_version=summary.extract_version,
        created_at=summary.created_at,
        updated_at=summary.updated_at
    )


@router.get(
    "/summaries",
    response_model=List[DiarySummarySimple],
    summary="查询多个日记摘要",
    description="查询多个日记摘要（简化版，供大模型使用）",
    responses={
        200: {"description": "成功获取摘要列表"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_summaries(
    user_id: EffectiveUserId,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    keywords: Optional[str] = None,
    emotions: Optional[str] = None,
    limit: int = 10,
    db: AsyncSession = Depends(get_db)
):
    """
    查询多个日记摘要（简化版，供大模型使用）
    
    支持日期范围过滤、关键词过滤、情绪过滤
    返回简化格式
    
    验证需求: 3.1, 3.2, 3.3, 5.1
    
    Args:
        user_id: 用户ID（从认证上下文获取，不允许传参）
        start_date: 开始日期（YYYY-MM-DD）
        end_date: 结束日期（YYYY-MM-DD）
        keywords: 关键词（逗号分隔）
        emotions: 情绪类型（逗号分隔）
        limit: 返回数量（1-100）
        db: 数据库会话
        
    Returns:
        List[DiarySummarySimple]: 摘要列表（简化格式）
        
    Security:
        - user_id 从认证上下文获取，不允许传参
        - 只返回当前用户的日记摘要
    """
    # 验证 limit
    if limit < 1 or limit > 100:
        raise AppValidationError(
            "limit 必须在 1-100 之间",
            details={"limit": limit, "valid_range": "1-100"}
        )
    
    # 导入 ExtractionService
    from app.services.extraction_service import ExtractionService
    
    extraction_service = ExtractionService(db)
    
    # 解析参数
    keyword_list = keywords.split(",") if keywords else None
    emotion_list = emotions.split(",") if emotions else None
    
    # 解析日期
    start_date_obj = None
    end_date_obj = None
    
    if start_date:
        try:
            start_date_obj = date_type.fromisoformat(start_date)
        except ValueError:
            raise AppValidationError(
                f"开始日期格式错误，应为YYYY-MM-DD格式: {start_date}",
                details={"start_date": start_date, "expected_format": "YYYY-MM-DD"}
            )
    
    if end_date:
        try:
            end_date_obj = date_type.fromisoformat(end_date)
        except ValueError:
            raise AppValidationError(
                f"结束日期格式错误，应为YYYY-MM-DD格式: {end_date}",
                details={"end_date": end_date, "expected_format": "YYYY-MM-DD"}
            )
    
    # 查询摘要
    summaries = await extraction_service.get_summaries(
        user_id=user_id,
        start_date=start_date_obj,
        end_date=end_date_obj,
        keywords=keyword_list,
        emotions=emotion_list,
        limit=limit
    )
    
    # 转换为响应模型
    return [DiarySummarySimple(**summary) for summary in summaries]


@router.get(
    "/summaries/export",
    summary="导出摘要文件",
    description="导出日记摘要文件（JSON或Markdown格式）",
    responses={
        200: {"description": "成功导出文件"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def export_summaries(
    user_id: EffectiveUserId,
    year: int,
    month: Optional[int] = None,
    format: str = "json",
    db: AsyncSession = Depends(get_db)
):
    """
    导出摘要文件
    
    支持 JSON 格式和 Markdown 格式
    按年/月导出
    
    验证需求: 2.1, 2.2, 2.3, 5.1
    
    Args:
        user_id: 用户ID（从认证上下文获取，不允许传参）
        year: 年份
        month: 月份（可选）
        format: 文件格式（json/markdown）
        db: 数据库会话
        
    Returns:
        文件下载响应
        
    Security:
        - user_id 从认证上下文获取，不允许传参
        - 只导出当前用户的日记摘要
    """
    # 验证参数
    if year < 2000 or year > 2100:
        raise AppValidationError(
            "年份无效",
            details={"year": year, "valid_range": "2000-2100"}
        )
    
    if month and (month < 1 or month > 12):
        raise AppValidationError(
            "月份无效",
            details={"month": month, "valid_range": "1-12"}
        )
    
    if format not in ["json", "markdown"]:
        raise AppValidationError(
            "格式无效，支持 json 或 markdown",
            details={"format": format, "valid_formats": ["json", "markdown"]}
        )
    
    # 导入 ExtractionService
    from app.services.extraction_service import ExtractionService
    from fastapi.responses import FileResponse
    import os
    
    extraction_service = ExtractionService(db)
    
    try:
        # 导出文件
        filepath = await extraction_service.export_summaries(
            user_id=user_id,
            year=year,
            month=month,
            format=format
        )
        
        # 返回文件
        return FileResponse(
            path=filepath,
            filename=os.path.basename(filepath),
            media_type="application/json" if format == "json" else "text/markdown"
        )
    except Exception as e:
        log_error(
            error=e,
            context={
                "user_id": user_id,
                "year": year,
                "month": month,
                "format": format
            },
            message="导出摘要文件失败"
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"导出摘要文件失败: {str(e)}"
        )
