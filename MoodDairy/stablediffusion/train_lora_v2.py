"""
Stable Diffusion LoRA 高效训练脚本
支持 Style1 和 Style2 两套风格的独立训练
无需 Accelerator，使用标准 PyTorch
"""
import os
import sys
import argparse
import logging
from pathlib import Path
from datetime import datetime

import torch
from torch.utils.data import Dataset, DataLoader
from torchvision import transforms
from PIL import Image
import numpy as np
from tqdm.auto import tqdm

from diffusers import StableDiffusionPipeline
from transformers import CLIPTextModel, CLIPTokenizer
from peft import LoraConfig, inject_adapter_in_model

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


class ImageDataset(Dataset):
    """训练图片数据集"""

    def __init__(
        self,
        data_dir: str,
        resolution: int = 512,
        style1_dir: str = None,
        style2_dir: str = None,
        style1_trigger: str = "STYLE1",
        style2_trigger: str = "STYLE2",
    ):
        self.resolution = resolution
        self.style1_trigger = style1_trigger
        self.style2_trigger = style2_trigger

        self.transform = transforms.Compose([
            transforms.Resize((resolution, resolution), interpolation=transforms.InterpolationMode.LANCZOS),
            transforms.ToTensor(),
            transforms.Normalize([0.5], [0.5]),
        ])

        self.samples = []

        if style1_dir and Path(style1_dir).exists():
            for img_file in Path(style1_dir).iterdir():
                if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                    self.samples.append((str(img_file), style1_trigger))

        if style2_dir and Path(style2_dir).exists():
            for img_file in Path(style2_dir).iterdir():
                if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                    self.samples.append((str(img_file), style2_trigger))

        if not self.samples:
            raise ValueError(f"No images found")

        logger.info(f"Found {len(self.samples)} images")
        style1_count = sum(1 for _, t in self.samples if t == style1_trigger)
        style2_count = sum(1 for _, t in self.samples if t == style2_trigger)
        logger.info(f"  {style1_trigger}: {style1_count}, {style2_trigger}: {style2_count}")

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        image_path, trigger = self.samples[idx]
        image = Image.open(image_path).convert("RGB")
        image = self.transform(image)
        prompt = f"a photo of {trigger}"
        return image, prompt, trigger


def train_lora(
    model_id: str,
    output_dir: str,
    style: str = "style1",
    lora_rank: int = 4,
    lora_alpha: int = 4,
    num_epochs: int = 15,
    batch_size: int = 1,
    learning_rate: float = 1e-4,
    resolution: int = 512,
    style1_trigger: str = "STYLE1",
    style2_trigger: str = "STYLE2",
    style1_dir: str = "data/images/style1",
    style2_dir: str = "data/images/style2",
):
    """训练 LoRA 模型"""

    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float32  # 使用 float32 避免混合精度问题
    logger.info(f"Using device: {device}, dtype: {dtype}")

    # 加载数据集
    style1_path = Path(style1_dir) if style == "style1" or style == "both" else None
    style2_path = Path(style2_dir) if style == "style2" or style == "both" else None

    if style == "style1" and not style1_path.exists():
        raise ValueError(f"Style1 dir not found: {style1_dir}")
    if style == "style2" and not style2_path.exists():
        raise ValueError(f"Style2 dir not found: {style2_dir}")

    train_dataset = ImageDataset(
        data_dir="data/images",
        resolution=resolution,
        style1_dir=str(style1_path) if style1_path else None,
        style2_dir=str(style2_path) if style2_path else None,
        style1_trigger=style1_trigger,
        style2_trigger=style2_trigger,
    )

    train_dataloader = DataLoader(
        train_dataset,
        batch_size=batch_size,
        shuffle=True,
        num_workers=0,
    )

    # 加载模型
    logger.info(f"Loading model: {model_id}")
    pipeline = StableDiffusionPipeline.from_pretrained(
        model_id,
        torch_dtype=torch.float32,
        safety_checker=None,
        requires_safety_checker=False,
    )

    # 确保所有模型组件在 float32
    pipeline.vae = pipeline.vae.float()
    pipeline.unet = pipeline.unet.float()

    # 设置 LoRA
    logger.info("Setting up LoRA...")
    lora_config = LoraConfig(
        r=lora_rank,
        lora_alpha=lora_alpha,
        lora_dropout=0.0,
        target_modules=["to_q", "to_k", "to_v", "to_out.0"],
        bias="none",
        inference_mode=False,
    )
    pipeline.unet = inject_adapter_in_model(lora_config, pipeline.unet)
    pipeline.unet.train()
    pipeline.unet = pipeline.unet.float()
    pipeline.unet.to(device)

    text_encoder_model = pipeline.text_encoder
    tokenizer = pipeline.tokenizer
    text_encoder_model = text_encoder_model.float()
    text_encoder_model.to(device)
    text_encoder_model.eval()

    # 优化器
    optimizer = torch.optim.AdamW(
        pipeline.unet.parameters(),
        lr=learning_rate,
        weight_decay=0.01,
    )

    # 训练
    progress_bar = tqdm(total=num_epochs * len(train_dataloader), desc="Training")

    for epoch in range(num_epochs):
        pipeline.unet.train()
        epoch_losses = []

        for batch_idx, (images, prompts, triggers) in enumerate(train_dataloader):
            images = images.to(device, dtype=dtype)

            with torch.no_grad():
                text_inputs = tokenizer(
                    prompts,
                    padding="max_length",
                    max_length=tokenizer.model_max_length,
                    truncation=True,
                    return_tensors="pt",
                )
                text_input_ids = text_inputs.input_ids.to(device)
                text_embeds = text_encoder_model(text_input_ids)[0]

            # VAE encode
            with torch.no_grad():
                images_fp32 = images.float()  # 确保是float32
                latents = pipeline.vae.encode(images_fp32).latent_dist.sample()
                latents = latents * pipeline.vae.config.scaling_factor

            # Add noise
            noise = torch.randn_like(latents)
            timesteps = torch.randint(
                0,
                pipeline.scheduler.config.num_train_timesteps,
                (latents.shape[0],),
                device=latents.device,
            ).long()

            noisy_latents = pipeline.scheduler.add_noise(latents, noise, timesteps)

            # Predict noise
            model_pred = pipeline.unet(
                noisy_latents, timesteps, encoder_hidden_states=text_embeds
            ).sample

            # Loss
            loss = torch.nn.functional.mse_loss(model_pred, noise, reduction="mean")

            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(pipeline.unet.parameters(), 1.0)
            optimizer.step()

            epoch_losses.append(loss.item())

            if batch_idx % 5 == 0:
                progress_bar.set_postfix({"epoch": epoch + 1, "loss": f"{loss.item():.4f}"})
            progress_bar.update(1)

        avg_loss = sum(epoch_losses) / len(epoch_losses)
        logger.info(f"Epoch {epoch + 1}/{num_epochs}, Avg Loss: {avg_loss:.4f}")

        if (epoch + 1) % 5 == 0:
            save_lora(pipeline, output_dir, f"epoch-{epoch + 1}", style)

    progress_bar.close()
    save_lora(pipeline, output_dir, "final", style)
    logger.info("Training complete!")
    return output_dir


def save_lora(pipeline, output_dir: str, suffix: str, style: str):
    """保存 LoRA"""
    output_path = Path(output_dir)

    if style == "style1":
        final_path = output_path / "style1" / suffix
    elif style == "style2":
        final_path = output_path / "style2" / suffix
    else:
        final_path = output_path / "both" / suffix

    final_path.mkdir(parents=True, exist_ok=True)
    pipeline.unet.save_pretrained(final_path)
    logger.info(f"Saved LoRA to {final_path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model_id", type=str, default="./stable-diffusion-v1-5")
    parser.add_argument("--output_dir", type=str, default="output/lora")
    parser.add_argument("--style", type=str, default="style1", choices=["style1", "style2", "both"])
    parser.add_argument("--lora_rank", type=int, default=4)
    parser.add_argument("--lora_alpha", type=int, default=4)
    parser.add_argument("--num_epochs", type=int, default=15)
    parser.add_argument("--batch_size", type=int, default=1)
    parser.add_argument("--learning_rate", type=float, default=1e-4)
    parser.add_argument("--resolution", type=int, default=512)
    parser.add_argument("--style1_trigger", type=str, default="STYLE1")
    parser.add_argument("--style2_trigger", type=str, default="STYLE2")
    parser.add_argument("--style1_dir", type=str, default="data/images/style1")
    parser.add_argument("--style2_dir", type=str, default="data/images/style2")

    args = parser.parse_args()

    logger.info("=" * 60)
    logger.info("LoRA Training:")
    logger.info(f"  Style: {args.style}")
    logger.info(f"  Epochs: {args.num_epochs}")
    logger.info(f"  LoRA Rank: {args.lora_rank}")
    logger.info("=" * 60)

    train_lora(
        model_id=args.model_id,
        output_dir=args.output_dir,
        style=args.style,
        lora_rank=args.lora_rank,
        lora_alpha=args.lora_alpha,
        num_epochs=args.num_epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        resolution=args.resolution,
        style1_trigger=args.style1_trigger,
        style2_trigger=args.style2_trigger,
        style1_dir=args.style1_dir,
        style2_dir=args.style2_dir,
    )


if __name__ == "__main__":
    main()
