"""
画画生成视频模块

提供画画和视频生成功能：
- 文生图：根据文字描述生成画作
- 图生图：增强用户简笔画为复杂画作
- 图生视频：将画作转换为消极→积极的动画视频

使用火山引擎(Volcengine) API 或 Stable Diffusion 本地生成
支持两种模式切换：
- volcengine: 火山引擎API模式
- stable_diffusion: Stable Diffusion本地LoRA模式
"""

from .volcengine_client import VolcengineClient
from .drawing_service_unified import DrawingService
from .sd_generator import SDGenerator, get_sd_generator
from .generator_config import (
    GeneratorConfig,
    GeneratorMode,
    SDConfig,
    get_current_mode,
    get_generator_config,
    set_generator_mode,
)
from .video_service import VideoService
from .drawing_router import router as drawing_router

__all__ = [
    "VolcengineClient",
    "DrawingService",
    "SDGenerator",
    "get_sd_generator",
    "GeneratorConfig",
    "GeneratorMode",
    "SDConfig",
    "get_current_mode",
    "get_generator_config",
    "set_generator_mode",
    "VideoService",
    "drawing_router",
]
