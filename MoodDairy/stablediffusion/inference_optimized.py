"""
Stable Diffusion LoRA 推理脚本
支持 Style1 和 Style2 两套风格切换
"""
import os
import argparse
import logging
from pathlib import Path
from datetime import datetime
import torch
from PIL import Image

from diffusers import StableDiffusionPipeline
from peft import PeftModel, inject_adapter_in_model

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)

DEFAULT_STYLE1_TRIGGER = "STYLE1"
DEFAULT_STYLE2_TRIGGER = "STYLE2"


def load_lora_pipeline(
    base_model_id: str,
    lora_path: str = None,
    device: str = None,
    dtype=torch.float16,
):
    """加载基础模型并合并 LoRA 权重"""

    if device is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"

    logger.info(f"Loading base model: {base_model_id}")
    logger.info(f"Using device: {device}")

    pipeline = StableDiffusionPipeline.from_pretrained(
        base_model_id,
        torch_dtype=dtype,
        safety_checker=None,
        requires_safety_checker=False,
    )

    if lora_path and Path(lora_path).exists():
        logger.info(f"Loading LoRA from: {lora_path}")
        pipeline.unet = PeftModel.from_pretrained(
            pipeline.unet,
            str(lora_path),
            torch_dtype=dtype,
        )
    else:
        logger.warning(f"LoRA path not found: {lora_path}, using base model")

    pipeline = pipeline.to(device)
    pipeline.enable_attention_slicing()

    return pipeline


def generate_images(
    pipeline: StableDiffusionPipeline,
    prompts: list,
    negative_prompt: str = "",
    num_inference_steps: int = 30,
    guidance_scale: float = 7.5,
    seed: int = None,
    height: int = 512,
    width: int = 512,
    num_images: int = 1,
    output_dir: str = "output",
):
    """生成图片"""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    for i, prompt in enumerate(prompts):
        logger.info(f"Generating image {i+1}/{len(prompts)}: {prompt}")

        if seed is not None:
            generator = torch.Generator(device=pipeline.device).manual_seed(seed + i)
        else:
            generator = None

        images = pipeline(
            prompt=prompt,
            negative_prompt=negative_prompt,
            num_inference_steps=num_inference_steps,
            guidance_scale=guidance_scale,
            height=height,
            width=width,
            num_images_per_prompt=num_images,
            generator=generator,
        ).images

        for j, img in enumerate(images):
            safe_prompt = "".join(c if c.isalnum() or c in " -_" else "_" for c in prompt)[:50]
            filename = f"image_{i+1}_{j+1}_{safe_prompt}.png"
            filepath = output_path / filename

            img.save(filepath)
            logger.info(f"  Saved: {filepath}")

    logger.info(f"\nAll images saved to: {output_path}")


def main():
    parser = argparse.ArgumentParser(description="Stable Diffusion LoRA Inference")

    parser.add_argument("--model_id", type=str, default="./stable-diffusion-v1-5", help="Base model path")
    parser.add_argument("--lora_path", type=str, default=None, help="Path to trained LoRA model")
    parser.add_argument(
        "--style",
        type=str,
        default="style1",
        choices=["style1", "style2", "both"],
        help="选择风格: style1 (STYLE1), style2 (STYLE2), 或 both (两种风格)",
    )

    parser.add_argument("--prompt", type=str, nargs="+", default=["a photo of a cat"], help="Prompt(s)")
    parser.add_argument("--negative_prompt", type=str, default="blurry, low quality, deformed", help="Negative prompt")
    parser.add_argument("--num_steps", type=int, default=30, help="Number of inference steps")
    parser.add_argument("--guidance_scale", type=float, default=7.5, help="Guidance scale")
    parser.add_argument("--seed", type=int, default=None, help="Random seed")
    parser.add_argument("--height", type=int, default=512, help="Image height")
    parser.add_argument("--width", type=int, default=512, help="Image width")
    parser.add_argument("--num_images", type=int, default=1, help="Number of images per prompt")
    parser.add_argument("--output_dir", type=str, default="output/inference", help="Output directory")

    args = parser.parse_args()

    if args.style == "style1":
        trigger = DEFAULT_STYLE1_TRIGGER
    elif args.style == "style2":
        trigger = DEFAULT_STYLE2_TRIGGER
    else:
        trigger = None

    logger.info("=" * 60)
    logger.info("Inference Configuration:")
    logger.info(f"  Model: {args.model_id}")
    logger.info(f"  LoRA: {args.lora_path}")
    logger.info(f"  Style: {args.style} (trigger: {trigger})")
    logger.info(f"  Prompts: {args.prompt}")
    logger.info(f"  Steps: {args.num_steps}")
    logger.info("=" * 60)

    final_prompts = []
    if args.style == "both":
        for p in args.prompt:
            final_prompts.append(f"{DEFAULT_STYLE1_TRIGGER} {p}")
            final_prompts.append(f"{DEFAULT_STYLE2_TRIGGER} {p}")
    elif trigger:
        for p in args.prompt:
            final_prompts.append(f"{trigger} {p}")
    else:
        final_prompts = args.prompt

    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32

    pipeline = load_lora_pipeline(
        base_model_id=args.model_id,
        lora_path=args.lora_path,
        device=device,
        dtype=dtype,
    )

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = f"{args.output_dir}/{args.style}_{timestamp}"

    generate_images(
        pipeline=pipeline,
        prompts=final_prompts,
        negative_prompt=args.negative_prompt,
        num_inference_steps=args.num_steps,
        guidance_scale=args.guidance_scale,
        seed=args.seed,
        height=args.height,
        width=args.width,
        num_images=args.num_images,
        output_dir=output_dir,
    )


if __name__ == "__main__":
    main()
