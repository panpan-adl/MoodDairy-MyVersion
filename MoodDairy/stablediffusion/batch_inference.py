"""
批量推理脚本 - 一次生成多张图片
"""

import torch
from diffusers import StableDiffusionPipeline
from peft import PeftModel, PeftConfig
import argparse
import os
from PIL import Image
import json

# 默认参数
DEFAULT_PROMPTS = [
    "STYLE a cat sitting on a chair",
    "STYLE a beautiful sunset over the ocean",
    "STYLE a portrait of a woman",
    "STYLE a fantasy landscape with mountains",
    "STYLE a still life with flowers"
]

DEFAULT_NEGATIVE_PROMPT = "low quality, blurry, distorted, deformed, bad anatomy"
DEFAULT_NUM_INFERENCE_STEPS = 50
DEFAULT_GUIDANCE_SCALE = 7.5
DEFAULT_SEED = 42

MODEL_ID = "stable-diffusion-v1-5/stable-diffusion-v1-5"


def load_pipeline_with_lora(
    model_id: str = MODEL_ID,
    lora_path: str = "output/lora",
    use_lora: bool = True
):
    """加载带有 LoRA 的 pipeline"""
    print(f"加载基础模型: {model_id}")
    
    pipeline = StableDiffusionPipeline.from_pretrained(
        model_id,
        torch_dtype=torch.float16,
        safety_checker=None,
        requires_safety_checker=False,
    )
    
    if use_lora and os.path.exists(lora_path):
        print(f"加载 LoRA: {lora_path}")
        pipeline.unet = PeftModel.from_pretrained(
            pipeline.unet,
            lora_path,
            torch_dtype=torch.float16
        )
    
    pipeline = pipeline.to("cuda")
    
    try:
        pipeline.enable_xformers_memory_efficient_attention()
    except ImportError:
        pass
    
    return pipeline


def generate_batch(
    pipeline,
    prompts: list,
    negative_prompt: str = DEFAULT_NEGATIVE_PROMPT,
    num_inference_steps: int = DEFAULT_NUM_INFERENCE_STEPS,
    guidance_scale: float = DEFAULT_GUIDANCE_SCALE,
    seed: int = DEFAULT_SEED,
    output_dir: str = "output/images"
):
    """批量生成图片"""
    os.makedirs(output_dir, exist_ok=True)
    
    if seed is not None:
        generator = torch.Generator(device="cuda").manual_seed(seed)
    
    for i, prompt in enumerate(prompts):
        print(f"\n[{i+1}/{len(prompts)}] 生成: {prompt}")
        
        image = pipeline(
            prompt=prompt,
            negative_prompt=negative_prompt,
            num_inference_steps=num_inference_steps,
            guidance_scale=guidance_scale,
            generator=generator
        ).images[0]
        
        # 生成文件名
        safe_name = prompt.replace(" ", "_")[:50]
        output_path = os.path.join(output_dir, f"{i+1:03d}_{safe_name}.png")
        
        image.save(output_path)
        print(f"保存到: {output_path}")
    
    print(f"\n完成！所有图片已保存到: {output_dir}")


def main():
    parser = argparse.ArgumentParser(description="批量生成图片")
    parser.add_argument("--prompts_file", type=str, help="提示词文件 (JSON 格式)")
    parser.add_argument("--prompt", type=str, action="append", help="单个提示词 (可多次使用)")
    parser.add_argument("--negative_prompt", type=str, default=DEFAULT_NEGATIVE_PROMPT)
    parser.add_argument("--num_steps", type=int, default=DEFAULT_NUM_INFERENCE_STEPS)
    parser.add_argument("--guidance_scale", type=float, default=DEFAULT_GUIDANCE_SCALE)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--output_dir", type=str, default="output/images")
    parser.add_argument("--lora_path", type=str, default="output/lora")
    parser.add_argument("--no_lora", action="store_true")
    
    args = parser.parse_args()
    
    # 获取提示词列表
    if args.prompts_file and os.path.exists(args.prompts_file):
        with open(args.prompts_file, 'r', encoding='utf-8') as f:
            prompts = json.load(f)
    elif args.prompt:
        prompts = args.prompt
    else:
        prompts = DEFAULT_PROMPTS
    
    print("=" * 50)
    print("Stable Diffusion LoRA 批量生成")
    print("=" * 50)
    print(f"生成 {len(prompts)} 张图片")
    
    pipeline = load_pipeline_with_lora(
        model_id=MODEL_ID,
        lora_path=args.lora_path,
        use_lora=not args.no_lora
    )
    
    generate_batch(
        pipeline,
        prompts=prompts,
        negative_prompt=args.negative_prompt,
        num_inference_steps=args.num_steps,
        guidance_scale=args.guidance_scale,
        seed=args.seed,
        output_dir=args.output_dir
    )


if __name__ == "__main__":
    main()
