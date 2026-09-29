"""
Stable Diffusion LoRA Inference Script
加载训练好的 LoRA 模型进行图片生成
支持两类风格：STYLE1 和 STYLE2
"""
import os
import argparse
from pathlib import Path
import torch
from PIL import Image

from diffusers import StableDiffusionPipeline
from peft import PeftModel, LoraConfig
import yaml

# 默认触发词
DEFAULT_STYLE1_TRIGGER = "STYLE1"
DEFAULT_STYLE2_TRIGGER = "STYLE2"


def load_lora_pipeline(
    base_model_id: str,
    lora_path: str,
    device: str = "cuda",
    dtype: torch.dtype = torch.float16,
):
    """
    加载基础模型并合并 LoRA 权重
    
    Args:
        base_model_id: 基础模型 ID (如 "sd-legacy/stable-diffusion-v1-5")
        lora_path: LoRA 模型路径
        device: 设备 ("cuda" 或 "cpu")
        dtype: 数据类型
    
    Returns:
        合并后的 pipeline
    """
    print(f"Loading base model: {base_model_id}")
    
    # Check if local path exists
    if Path(base_model_id).exists():
        print(f"Loading from local path: {base_model_id}")
    
    # Load base pipeline
    pipeline = StableDiffusionPipeline.from_pretrained(
        base_model_id,
        torch_dtype=dtype,
        safety_checker=None,  # Disable for faster inference
        requires_safety_checker=False,
    )
    
    # Check if LoRA path exists
    lora_path = Path(lora_path)
    if not lora_path.exists():
        print(f"Warning: LoRA path not found: {lora_path}")
        print("Using base model without LoRA...")
        pipeline = pipeline.to(device)
        return pipeline
    
    print(f"Loading LoRA from: {lora_path}")
    
    # Load LoRA weights
    pipeline.unet = PeftModel.from_pretrained(
        pipeline.unet,
        str(lora_path),
        torch_dtype=dtype,
    )
    
    # Merge LoRA weights for inference (optional - can also use with LoRA)
    # pipeline.unet = pipeline.unet.merge_and_unload()
    
    pipeline = pipeline.to(device)
    
    # Enable memory efficient attention if available
    try:
        pipeline.enable_attention_slicing()
    except:
        pass
    
    print("Model loaded successfully!")
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
    """
    生成图片
    
    Args:
        pipeline: 加载好的 pipeline
        prompts: 提示词列表
        negative_prompt: 负面提示词
        num_inference_steps: 推理步数
        guidance_scale: 引导强度
        seed: 随机种子
        height: 图片高度
        width: 图片宽度
        num_images: 每个提示词生成几张图
        output_dir: 输出目录
    """
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    for i, prompt in enumerate(prompts):
        print(f"\nGenerating image {i+1}/{len(prompts)}: {prompt}")
        
        # Set seed for reproducibility
        if seed is not None:
            generator = torch.Generator(device=pipeline.device).manual_seed(seed + i)
        else:
            generator = None
        
        # Generate
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
        
        # Save images
        for j, img in enumerate(images):
            # Create filename from prompt (truncated)
            safe_prompt = "".join(c if c.isalnum() or c in " -_" else "_" for c in prompt)[:50]
            filename = f"image_{i+1}_{j+1}_{safe_prompt}.png"
            filepath = output_path / filename
            
            img.save(filepath)
            print(f"  Saved: {filepath}")
    
    print(f"\nAll images saved to: {output_path}")


def main():
    parser = argparse.ArgumentParser(description="Stable Diffusion LoRA Inference")
    
    # Model arguments - 使用本地模型路径
    parser.add_argument("--model_id", type=str, default="./stable-diffusion-v1-5",
                        help="Base model ID or path")
    parser.add_argument("--lora_path", type=str, default="output/lora/final",
                        help="Path to trained LoRA model")
    
    # 风格选择
    parser.add_argument("--style", type=str, default="style1", choices=["style1", "style2", "both"],
                        help="选择风格: style1 (STYLE1), style2 (STYLE2), 或 both (两种风格)")
    
    # Generation arguments
    parser.add_argument("--prompt", type=str, nargs="+", 
                        default=["a photo of a cat"],
                        help="Prompt(s) for generation")
    parser.add_argument("--negative_prompt", type=str, default="blurry, low quality",
                        help="Negative prompt")
    parser.add_argument("--num_steps", type=int, default=30,
                        help="Number of inference steps")
    parser.add_argument("--guidance_scale", type=float, default=7.5,
                        help="Guidance scale")
    parser.add_argument("--seed", type=int, default=None,
                        help="Random seed for reproducibility")
    parser.add_argument("--height", type=int, default=512,
                        help="Image height")
    parser.add_argument("--width", type=int, default=512,
                        help="Image width")
    parser.add_argument("--num_images", type=int, default=1,
                        help="Number of images per prompt")
    parser.add_argument("--output_dir", type=str, default="output/inference",
                        help="Output directory")
    
    args = parser.parse_args()
    
    # 确定触发词
    if args.style == "style1":
        trigger = DEFAULT_STYLE1_TRIGGER
        print(f"Using style: {trigger}")
    elif args.style == "style2":
        trigger = DEFAULT_STYLE2_TRIGGER
        print(f"Using style: {trigger}")
    else:  # both
        print(f"Generating with both styles...")
    
    # 构建提示词
    final_prompts = []
    if args.style == "both":
        for p in args.prompt:
            final_prompts.append(f"{DEFAULT_STYLE1_TRIGGER} {p}")
            final_prompts.append(f"{DEFAULT_STYLE2_TRIGGER} {p}")
    else:
        for p in args.prompt:
            final_prompts.append(f"{trigger} {p}")
    
    # Determine device
    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32
    
    print(f"Using device: {device}")
    print(f"Data type: {dtype}")
    
    # Load pipeline
    pipeline = load_lora_pipeline(
        base_model_id=args.model_id,
        lora_path=args.lora_path,
        device=device,
        dtype=dtype,
    )
    
    # Generate images
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
        output_dir=args.output_dir,
    )


if __name__ == "__main__":
    main()
