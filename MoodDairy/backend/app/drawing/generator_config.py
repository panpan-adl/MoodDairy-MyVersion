"""
图片生成器配置
支持两种生成流程切换：
1. volcengine - 使用火山引擎API生成图片（当前流程）
2. stable_diffusion - 使用本地Stable Diffusion + LoRA微调
"""

from enum import Enum
from pathlib import Path
from typing import Optional
import os


class GeneratorMode(str, Enum):
    """生成器模式枚举"""
    VOLCENGINE = "volcengine"           # 火山引擎API模式
    STABLE_DIFFUSION = "stable_diffusion"  # Stable Diffusion本地模式


class SDConfig:
    """Stable Diffusion 配置"""

    # 模型路径 (使用绝对路径)
    BASE_DIR = Path(__file__).parent.parent.parent.parent / "stablediffusion"
    MODEL_ID: str = str(BASE_DIR / "stable-diffusion-v1-5")
    LORA_PATH: str = str(BASE_DIR / "output" / "lora")

    # 风格触发词
    STYLE1_TRIGGER: str = "STYLE1"
    STYLE2_TRIGGER: str = "STYLE2"

    # LoRA路径
    STYLE1_LORA: str = str(BASE_DIR / "output" / "lora" / "style1" / "final")
    STYLE2_LORA: str = str(BASE_DIR / "output" / "lora" / "style2" / "final")

    # 推理参数
    NUM_INFERENCE_STEPS: int = 30
    GUIDANCE_SCALE: float = 7.5
    SEED: Optional[int] = None
    HEIGHT: int = 512
    WIDTH: int = 512
    NEGATIVE_PROMPT: str = "blurry, low quality, deformed, ugly, bad anatomy"

    @classmethod
    def get_lora_path(cls, style: str) -> str:
        """获取指定风格的LoRA路径"""
        if style == "style1":
            return cls.STYLE1_LORA
        elif style == "style2":
            return cls.STYLE2_LORA
        else:
            return cls.LORA_PATH

    @classmethod
    def get_trigger(cls, style: str) -> str:
        """获取指定风格的触发词"""
        if style == "style1":
            return cls.STYLE1_TRIGGER
        elif style == "style2":
            return cls.STYLE2_TRIGGER
        else:
            return cls.STYLE1_TRIGGER


class GeneratorConfig:
    """主配置类"""

    # Stable Diffusion 总开关 - 控制是否启用 SD 功能
    # 可通过环境变量 SD_ENABLED 设置，默认为 true（启用）
    _sd_enabled: bool = True

    # AI 绘图总开关 - 控制移动端是否显示 AI 绘图功能
    # 可通过环境变量 DRAWING_ENABLED 设置，默认为 true（启用）
    _drawing_enabled: bool = True

    # 当前使用的生成器模式
    # 可通过环境变量 GENERATOR_MODE 设置
    # 或运行时动态切换
    MODE: GeneratorMode = GeneratorMode.VOLCENGINE

    # Stable Diffusion 配置
    SD: SDConfig = SDConfig()

    @classmethod
    def set_sd_enabled(cls, enabled: bool):
        """设置SD功能开关"""
        cls._sd_enabled = enabled
        # 如果关闭SD功能，强制切换到Volcengine模式
        if not enabled and cls.MODE == GeneratorMode.STABLE_DIFFUSION:
            cls.MODE = GeneratorMode.VOLCENGINE

    @classmethod
    def is_sd_enabled(cls) -> bool:
        """获取SD功能开关状态"""
        return cls._sd_enabled

    @classmethod
    def set_drawing_enabled(cls, enabled: bool):
        """设置AI绘图功能开关（控制移动端显示）"""
        cls._drawing_enabled = enabled

    @classmethod
    def is_drawing_enabled(cls) -> bool:
        """获取AI绘图功能开关状态"""
        return cls._drawing_enabled

    @classmethod
    def set_mode(cls, mode: GeneratorMode):
        """设置生成器模式"""
        cls.MODE = mode

    @classmethod
    def get_mode(cls) -> GeneratorMode:
        """获取当前生成器模式"""
        return cls.MODE

    @classmethod
    def init_from_env(cls):
        """从环境变量初始化配置"""
        # 初始化 SD 开关
        sd_enabled_str = os.environ.get("SD_ENABLED", "true").lower()
        cls._sd_enabled = sd_enabled_str not in ("false", "0", "no", "off")

        # 初始化 AI 绘图开关（控制移动端显示）
        drawing_enabled_str = os.environ.get("DRAWING_ENABLED", "true").lower()
        cls._drawing_enabled = drawing_enabled_str not in ("false", "0", "no", "off")

        # 初始化生成器模式
        mode_str = os.environ.get("GENERATOR_MODE", "volcengine").lower()
        if mode_str == "stable_diffusion":
            cls.MODE = GeneratorMode.STABLE_DIFFUSION
        else:
            cls.MODE = GeneratorMode.VOLCENGINE


def get_generator_config() -> GeneratorConfig:
    """获取生成器配置单例"""
    return GeneratorConfig


def set_generator_mode(mode: GeneratorMode):
    """快捷函数：设置生成器模式"""
    GeneratorConfig.set_mode(mode)


def get_current_mode() -> GeneratorMode:
    """快捷函数：获取当前生成器模式"""
    return GeneratorConfig.get_mode()


def set_sd_enabled(enabled: bool):
    """快捷函数：设置SD功能开关"""
    GeneratorConfig.set_sd_enabled(enabled)


def is_sd_enabled() -> bool:
    """快捷函数：获取SD功能开关状态"""
    return GeneratorConfig.is_sd_enabled()


def set_drawing_enabled(enabled: bool):
    """快捷函数：设置AI绘图功能开关（控制移动端显示）"""
    GeneratorConfig.set_drawing_enabled(enabled)


def is_drawing_enabled() -> bool:
    """快捷函数：获取AI绘图功能开关状态"""
    return GeneratorConfig.is_drawing_enabled()
