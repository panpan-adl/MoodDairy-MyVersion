"""
Schemas for drawing and video APIs.

支持双流程的图片生成请求
"""

from typing import Optional

from pydantic import BaseModel, Field


DEFAULT_DRAWING_SIZE = "2048x2048"


class DrawingGenerateRequest(BaseModel):
    """Text-to-image request."""

    prompt: str = Field(..., description="Prompt", min_length=1, max_length=2000)
    size: str = Field(default=DEFAULT_DRAWING_SIZE, description="Image size")
    watermark: bool = Field(default=False, description="Whether to add watermark")


class DrawingEnhanceRequest(BaseModel):
    """Sketch enhancement request."""

    image_url: str = Field(..., description="Source image URL or data URL")
    prompt: str = Field(default="", description="Optional enhancement prompt", max_length=2000)
    size: str = Field(default=DEFAULT_DRAWING_SIZE, description="Output image size")
    watermark: bool = Field(default=False, description="Whether to add watermark")


class SketchUploadResponse(BaseModel):
    """Upload response for sketch inputs."""

    image_url: str = Field(..., description="Uploaded sketch URL")
    message: str = Field(default="Sketch uploaded successfully")


class DrawingFromDiaryRequest(BaseModel):
    """Diary-to-image request."""

    style: str = Field(default="watercolor", description="Drawing style")
    include_emotion_transform: bool = Field(
        default=True,
        description="Whether to include emotional transformation hints",
    )
    # 新增：可选的自定义提示词（用户选择推荐或自定义）
    prompt: Optional[str] = Field(
        default=None,
        description="Custom prompt to use (if provided, overrides emotion-based prompt)",
    )


class DrawingResponse(BaseModel):
    """Drawing response."""

    image_url: str = Field(..., description="Generated image URL")
    prompt_used: str = Field(..., description="Prompt actually used")
    message: str = Field(default="Drawing generated successfully")


class VideoGenerateRequest(BaseModel):
    """Image-to-video request."""

    image_url: str = Field(..., description="Source image URL")
    prompt: str = Field(..., description="Video prompt", min_length=1, max_length=500)
    duration: int = Field(default=5, ge=3, le=10, description="Duration in seconds")
    camera_fixed: bool = Field(default=False, description="Whether camera is fixed")
    watermark: bool = Field(default=True, description="Whether to add watermark")


class VideoTransformRequest(BaseModel):
    """Emotion transform video request."""

    image_url: str = Field(..., description="Source image URL")
    transformation_type: str = Field(
        default="negative_to_positive",
        description="Transformation type",
    )
    duration: int = Field(default=5, ge=3, le=10, description="Duration in seconds")


class VideoTaskResponse(BaseModel):
    """Video task creation response."""

    task_id: str = Field(..., description="Task ID")
    status: str = Field(..., description="Task status")
    message: str = Field(default="Video task created")


class VideoStatusResponse(BaseModel):
    """Video task status response."""

    task_id: str = Field(..., description="Task ID")
    status: str = Field(..., description="Task status")
    video_url: Optional[str] = Field(None, description="Generated video URL")
    error: Optional[str] = Field(None, description="Error message")


class VideoResponse(BaseModel):
    """Final video response."""

    video_url: str = Field(..., description="Generated video URL")
    duration: int = Field(..., description="Duration in seconds")
    task_id: str = Field(..., description="Task ID")
    message: str = Field(default="Video generated successfully")


# ========== 情绪推荐提示词相关 ==========

class EmotionPrompts(BaseModel):
    """单套情绪提示词"""

    emotion_type: str = Field(..., description="情绪类型: positive/negative")
    display_name: str = Field(..., description="显示名称（给用户看）")
    prompt_preview: str = Field(..., description="提示词预览（简短）")
    full_prompt: str = Field(..., description="完整提示词模板")


class PromptRecommendationResponse(BaseModel):
    """提示词推荐响应"""

    detected_emotion: str = Field(..., description="检测到的情绪")
    emotion_score: int = Field(..., description="情绪分数 1-100")
    recommendations: list[EmotionPrompts] = Field(..., description="推荐的提示词列表")


# ========== 生成器模式切换相关 ==========

class GeneratorModeResponse(BaseModel):
    """生成器模式响应"""

    mode: str = Field(..., description="当前模式: volcengine / stable_diffusion")
    description: str = Field(..., description="模式描述")
    available_modes: list["GeneratorModeResponse.AvailableMode"] = Field(
        default_factory=list, description="可用的模式列表"
    )

    class AvailableMode(BaseModel):
        """可用模式"""

        value: str = Field(..., description="模式值")
        name: str = Field(..., description="模式名称")
        description: str = Field(..., description="模式描述")


# ========== SD 开关相关 ==========

class SDEnabledResponse(BaseModel):
    """SD 功能开关状态响应"""

    sd_enabled: bool = Field(..., description="SD 功能是否启用")
    current_mode: str = Field(..., description="当前使用的生成模式")
    available_modes: list["GeneratorModeResponse.AvailableMode"] = Field(
        default_factory=list, description="可用的模式列表（SD 启用时包含 SD 模式）"
    )
    message: str = Field(..., description="状态描述信息")


class SDEnabledRequest(BaseModel):
    """SD 功能开关设置请求"""

    sd_enabled: bool = Field(..., description="是否启用 SD 功能")


# ========== 移动端 AI 绘图显示控制相关 ==========

class DrawingEnabledResponse(BaseModel):
    """AI 绘图功能开关状态响应（用于控制移动端显示）"""

    drawing_enabled: bool = Field(..., description="AI 绘图功能是否启用（影响移动端显示）")
    message: str = Field(..., description="状态描述信息")
