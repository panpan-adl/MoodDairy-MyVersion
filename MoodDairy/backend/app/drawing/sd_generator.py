"""
Stable Diffusion 本地生成器
使用训练好的 LoRA 模型生成图片
"""

import asyncio
import logging
import os
from pathlib import Path
from typing import Optional
import torch
from PIL import Image
from io import BytesIO
import base64

from diffusers import StableDiffusionPipeline

from .generator_config import GeneratorConfig, SDConfig, GeneratorMode, get_current_mode

logger = logging.getLogger(__name__)


class SDGeneratorError(Exception):
    """SD生成器错误"""


class SDGenerator:
    """Stable Diffusion 本地生成器"""

    _pipeline: Optional[StableDiffusionPipeline] = None
    _current_lora: Optional[str] = None
    _lock: asyncio.Lock = None

    def __init__(self):
        self.config = SDConfig()
        if self._lock is None:
            SDGenerator._lock = asyncio.Lock()

    @classmethod
    def get_lock(cls) -> asyncio.Lock:
        if cls._lock is None:
            cls._lock = asyncio.Lock()
        return cls._lock

    def _get_device(self) -> str:
        """获取设备"""
        return "cuda" if torch.cuda.is_available() else "cpu"

    def _get_dtype(self) -> torch.dtype:
        """获取数据类型"""
        return torch.float16 if self._get_device() == "cuda" else torch.float32

    async def _load_pipeline(self, lora_path: Optional[str] = None) -> StableDiffusionPipeline:
        """加载/复用pipeline"""
        async with self.get_lock():
            if self._pipeline is not None and self._current_lora == lora_path:
                logger.info(f"Reusing existing pipeline (LoRA: {lora_path})")
                return self._pipeline

            if self._pipeline is not None:
                del self._pipeline
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()

            logger.info(f"Loading SD pipeline: model={self.config.MODEL_ID}, lora={lora_path}")

            pipeline = StableDiffusionPipeline.from_pretrained(
                self.config.MODEL_ID,
                torch_dtype=self._get_dtype(),
                safety_checker=None,
                requires_safety_checker=False,
            )

            if lora_path and Path(lora_path).exists():
                logger.info(f"Loading LoRA: {lora_path}")
                try:
                    from peft import PeftModel
                    pipeline.unet = PeftModel.from_pretrained(
                        pipeline.unet,
                        lora_path,
                        torch_dtype=self._get_dtype(),
                    )
                    self._current_lora = lora_path
                except Exception as e:
                    logger.warning(f"Failed to load LoRA: {e}, using base model")
                    self._current_lora = None
            else:
                self._current_lora = None

            pipeline = pipeline.to(self._get_device())
            pipeline.enable_attention_slicing()

            self._pipeline = pipeline
            return pipeline

    def _image_to_base64(self, image: Image.Image) -> str:
        """将PIL Image转换为base64"""
        buffered = BytesIO()
        image.save(buffered, format="PNG")
        return base64.b64encode(buffered.getvalue()).decode()

    async def text_to_image(
        self,
        prompt: str,
        style: str = "style1",
        size: str = "512x512",
        **kwargs,
    ) -> str:
        """使用LoRA生成图片，返回base64编码的图片URL"""

        lora_path = self.config.get_lora_path(style)
        trigger = self.config.get_trigger(style)
        full_prompt = f"{trigger} {prompt}"

        height, width = self._parse_size(size)

        pipeline = await self._load_pipeline(lora_path)

        loop = asyncio.get_running_loop()

        def generate():
            return pipeline(
                prompt=full_prompt,
                negative_prompt=self.config.NEGATIVE_PROMPT,
                num_inference_steps=self.config.NUM_INFERENCE_STEPS,
                guidance_scale=self.config.GUIDANCE_SCALE,
                height=height,
                width=width,
                generator=torch.Generator(device=self._get_device()).manual_seed(self.config.SEED or 42),
            ).images[0]

        image = await loop.run_in_executor(None, generate)
        return f"data:image/png;base64,{self._image_to_base64(image)}"

    async def image_to_image(
        self,
        image_url: str,
        prompt: str,
        style: str = "style1",
        size: str = "512x512",
        **kwargs,
    ) -> str:
        """使用LoRA进行图生图，返回base64编码的图片URL"""

        lora_path = self.config.get_lora_path(style)
        trigger = self.config.get_trigger(style)
        full_prompt = f"{trigger} {prompt}"

        source_image = await self._load_image_from_url(image_url)
        height, width = self._parse_size(size)

        pipeline = await self._load_pipeline(lora_path)

        loop = asyncio.get_running_loop()

        def generate():
            return pipeline(
                prompt=full_prompt,
                negative_prompt=self.config.NEGATIVE_PROMPT,
                num_inference_steps=self.config.NUM_INFERENCE_STEPS,
                guidance_scale=self.config.GUIDANCE_SCALE,
                height=height,
                width=width,
                image=source_image,
                strength=0.75,
                generator=torch.Generator(device=self._get_device()).manual_seed(self.config.SEED or 42),
            ).images[0]

        image = await loop.run_in_executor(None, generate)
        return f"data:image/png;base64,{self._image_to_base64(image)}"

    async def _load_image_from_url(self, image_url: str) -> Image.Image:
        """从URL加载图片"""
        import requests

        if image_url.startswith("data:image"):
            pass
        elif image_url.startswith("http"):
            response = requests.get(image_url)
            response.raise_for_status()
            return Image.open(BytesIO(response.content)).convert("RGB")
        else:
            return Image.open(image_url).convert("RGB")

    def _parse_size(self, size: str) -> tuple:
        """解析尺寸字符串"""
        if "x" in size:
            width, height = size.split("x")
            return int(height), int(width)
        return 512, 512


_sd_generator: Optional[SDGenerator] = None


def get_sd_generator() -> SDGenerator:
    """获取SD生成器单例"""
    global _sd_generator
    if _sd_generator is None:
        _sd_generator = SDGenerator()
    return _sd_generator
