"""
API routes for drawing and video generation.

支持双流程模式切换
"""

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.services.media_service import MediaService

from .drawing_service_unified import DrawingService, DrawingServiceError
from .generator_config import GeneratorMode, set_generator_mode, get_current_mode, is_sd_enabled, set_sd_enabled, is_drawing_enabled
from .schemas import (
    DrawingEnhanceRequest,
    DrawingEnabledResponse,
    DrawingFromDiaryRequest,
    DrawingGenerateRequest,
    DrawingResponse,
    PromptRecommendationResponse,
    SketchUploadResponse,
    VideoGenerateRequest,
    VideoStatusResponse,
    VideoTaskResponse,
    VideoTransformRequest,
    GeneratorModeResponse,
    SDEnabledResponse,
    SDEnabledRequest,
)
from .video_service import VideoService, VideoServiceError

router = APIRouter(prefix="/drawing", tags=["drawing"])


@router.post(
    "/generate",
    response_model=DrawingResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Generate an image from text",
)
async def generate_drawing(request: DrawingGenerateRequest) -> DrawingResponse:
    try:
        service = DrawingService()
        result = await service.generate_from_text(
            prompt=request.prompt,
            size=request.size,
            watermark=request.watermark,
        )
        return DrawingResponse(
            image_url=result["image_url"],
            prompt_used=result["prompt_used"],
            message="Drawing generated successfully",
        )
    except DrawingServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.post(
    "/sketch-upload",
    response_model=SketchUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload a sketch image for enhancement",
)
async def upload_sketch(file: UploadFile = File(...)) -> SketchUploadResponse:
    content_type = (file.content_type or "").lower()
    if not content_type.startswith("image/"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only image uploads are supported for sketch enhancement",
        )

    try:
        media_service = MediaService()
        file_bytes = await file.read()
        upload_result = await media_service.upload_media_bytes(
            file_bytes=file_bytes,
            media_type="image",
            content_type=file.content_type or "image/png",
            filename=file.filename or "sketch.png",
        )
        return SketchUploadResponse(
            image_url=upload_result["url"],
            message="Sketch uploaded successfully",
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Sketch upload failed: {exc}",
        ) from exc


@router.post(
    "/enhance",
    response_model=DrawingResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Enhance a sketch",
)
async def enhance_sketch(request: DrawingEnhanceRequest) -> DrawingResponse:
    try:
        service = DrawingService()
        result = await service.enhance_sketch(
            image_url=request.image_url,
            enhancement_prompt=request.prompt,
            size=request.size,
            watermark=request.watermark,
        )
        return DrawingResponse(
            image_url=result["image_url"],
            prompt_used=result["prompt_used"],
            message="Sketch enhanced successfully",
        )
    except DrawingServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.post(
    "/from-diary/{diary_id}",
    response_model=DrawingResponse,
    status_code=status.HTTP_201_CREATED,
    include_in_schema=False,
)
@router.post(
    "/{diary_id}/from-diary",
    response_model=DrawingResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Generate an image from a diary entry",
)
async def generate_from_diary(
    diary_id: int,
    request: DrawingFromDiaryRequest | None = None,
    db: AsyncSession = Depends(get_db),
) -> DrawingResponse:
    try:
        service = DrawingService()
        style = request.style if request else "watercolor"
        include_transform = request.include_emotion_transform if request else True
        custom_prompt = request.prompt if request and request.prompt else None
        result = await service.generate_from_diary(
            db=db,
            diary_id=diary_id,
            style=style,
            include_emotion_transform=include_transform,
            custom_prompt=custom_prompt,
        )
        return DrawingResponse(
            image_url=result["image_url"],
            prompt_used=result["prompt_used"],
            message="Drawing generated from diary successfully",
        )
    except DrawingServiceError as exc:
        status_code = status.HTTP_404_NOT_FOUND if "not found" in str(exc).lower() else status.HTTP_500_INTERNAL_SERVER_ERROR
        raise HTTPException(status_code=status_code, detail=str(exc)) from exc


@router.get(
    "/{diary_id}/prompt-recommendations",
    response_model=PromptRecommendationResponse,
    summary="Get emotion-based prompt recommendations for diary",
)
async def get_prompt_recommendations(
    diary_id: int,
    db: AsyncSession = Depends(get_db),
) -> PromptRecommendationResponse:
    try:
        service = DrawingService()
        result = await service.get_emotion_prompts(db=db, diary_id=diary_id)
        return PromptRecommendationResponse(**result)
    except DrawingServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.post(
    "/to-video",
    response_model=VideoTaskResponse,
    status_code=status.HTTP_202_ACCEPTED,
    include_in_schema=False,
)
@router.post(
    "/video/create",
    response_model=VideoTaskResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Create a video from an image",
)
async def create_video(request: VideoGenerateRequest) -> VideoTaskResponse:
    try:
        service = VideoService()
        result = await service.create_video_from_image(
            image_url=request.image_url,
            prompt=request.prompt,
            duration=request.duration,
            camera_fixed=request.camera_fixed,
            watermark=request.watermark,
        )
        return VideoTaskResponse(
            task_id=result["task_id"],
            status=result["status"],
            message="Video task created",
        )
    except VideoServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.post(
    "/transform-video",
    response_model=VideoTaskResponse,
    status_code=status.HTTP_202_ACCEPTED,
    include_in_schema=False,
)
@router.post(
    "/video/transform",
    response_model=VideoTaskResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Create an emotion-transform video",
)
async def create_transform_video(request: VideoTransformRequest) -> VideoTaskResponse:
    try:
        service = VideoService()
        result = await service.create_transformation_video(
            image_url=request.image_url,
            transformation_type=request.transformation_type,
            duration=request.duration,
        )
        return VideoTaskResponse(
            task_id=result["task_id"],
            status=result["status"],
            message="Transformation video task created",
        )
    except VideoServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.get(
    "/video-status/{task_id}",
    response_model=VideoStatusResponse,
    include_in_schema=False,
)
@router.get(
    "/video/status/{task_id}",
    response_model=VideoStatusResponse,
    summary="Get video generation status",
)
async def get_video_status(task_id: str) -> VideoStatusResponse:
    try:
        service = VideoService()
        result = await service.get_video_status(task_id)
        return VideoStatusResponse(
            task_id=result["task_id"],
            status=result["status"],
            video_url=result.get("video_url"),
            error=result.get("error"),
        )
    except VideoServiceError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


# ========== 生成模式切换相关 ==========

@router.get(
    "/mode",
    response_model=GeneratorModeResponse,
    summary="Get current generator mode",
)
async def get_generator_mode() -> GeneratorModeResponse:
    """获取当前使用的图片生成模式"""
    current_mode = get_current_mode()
    sd_enabled = is_sd_enabled()

    # 如果 SD 被禁用，强制显示 volcengine 模式
    if not sd_enabled:
        actual_mode = GeneratorMode.VOLCENGINE
    else:
        actual_mode = current_mode

    return GeneratorModeResponse(
        mode=actual_mode.value,
        description=actual_mode.value,
        available_modes=_build_available_modes(include_sd=sd_enabled),
    )


@router.post(
    "/mode",
    response_model=GeneratorModeResponse,
    summary="Switch generator mode",
)
async def switch_generator_mode(mode: str) -> GeneratorModeResponse:
    """
    切换图片生成模式

    - volcengine: 使用火山引擎API（默认）
    - stable_diffusion: 使用本地Stable Diffusion + LoRA（仅在 SD 启用时可用）
    """
    # 检查 SD 是否被禁用
    if not is_sd_enabled() and mode == GeneratorMode.STABLE_DIFFUSION.value:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="SD 功能已被管理员禁用，无法切换到 Stable Diffusion 模式。请先启用 SD 功能。",
        )

    try:
        new_mode = GeneratorMode(mode)
        set_generator_mode(new_mode)
        return GeneratorModeResponse(
            mode=new_mode.value,
            description=f"已切换到 {new_mode.value} 模式",
            available_modes=_build_available_modes(include_sd=is_sd_enabled()),
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid mode: {mode}. Available modes: volcengine, stable_diffusion",
        )


# ========== SD 功能开关相关 ==========

def _build_available_modes(include_sd: bool = True) -> list[GeneratorModeResponse.AvailableMode]:
    """构建可用模式列表"""
    modes = [
        GeneratorModeResponse.AvailableMode(
            value=GeneratorMode.VOLCENGINE.value,
            name="火山引擎",
            description="使用火山引擎豆包API生成图片",
        ),
    ]
    if include_sd:
        modes.append(
            GeneratorModeResponse.AvailableMode(
                value=GeneratorMode.STABLE_DIFFUSION.value,
                name="Stable Diffusion",
                description="使用本地Stable Diffusion + LoRA微调模型",
            )
        )
    return modes


@router.get(
    "/sd-enabled",
    response_model=SDEnabledResponse,
    summary="Get SD feature enabled status",
)
async def get_sd_enabled() -> SDEnabledResponse:
    """获取 SD 功能的启用状态"""
    sd_enabled = is_sd_enabled()
    current_mode = get_current_mode()

    # 如果 SD 被禁用，强制使用 volcengine 模式
    if not sd_enabled:
        actual_mode = GeneratorMode.VOLCENGINE
    else:
        actual_mode = current_mode

    return SDEnabledResponse(
        sd_enabled=sd_enabled,
        current_mode=actual_mode.value,
        available_modes=_build_available_modes(include_sd=sd_enabled),
        message="SD 功能已启用" if sd_enabled else "SD 功能已禁用，当前使用火山引擎模式",
    )


@router.post(
    "/sd-enabled",
    response_model=SDEnabledResponse,
    summary="Set SD feature enabled status",
)
async def set_sd_feature_enabled(request: SDEnabledRequest) -> SDEnabledResponse:
    """
    设置 SD 功能的启用状态

    - sd_enabled=true: 启用 SD 功能
    - sd_enabled=false: 禁用 SD 功能，强制使用火山引擎模式
    """
    set_sd_enabled(request.sd_enabled)
    sd_enabled = is_sd_enabled()
    current_mode = get_current_mode()

    # 如果 SD 被禁用，强制使用 volcengine 模式
    if not sd_enabled:
        actual_mode = GeneratorMode.VOLCENGINE
    else:
        actual_mode = current_mode

    return SDEnabledResponse(
        sd_enabled=sd_enabled,
        current_mode=actual_mode.value,
        available_modes=_build_available_modes(include_sd=sd_enabled),
        message="SD 功能已启用" if sd_enabled else "SD 功能已禁用，当前使用火山引擎模式",
    )


# ========== 移动端 AI 绘图显示控制 ==========

@router.get(
    "/enabled",
    response_model=DrawingEnabledResponse,
    summary="Get AI drawing feature enabled status for mobile",
)
async def get_drawing_enabled() -> DrawingEnabledResponse:
    """
    获取 AI 绘图功能在移动端的启用状态

    - drawing_enabled=true: 移动端显示 AI 绘图功能
    - drawing_enabled=false: 移动端不显示 AI 绘图功能

    该状态由环境变量 DRAWING_ENABLED 控制，默认值为 true。
    """
    drawing_enabled = is_drawing_enabled()
    return DrawingEnabledResponse(
        drawing_enabled=drawing_enabled,
        message="AI 绘图功能已启用" if drawing_enabled else "AI 绘图功能已禁用",
    )
