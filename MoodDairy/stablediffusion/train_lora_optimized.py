"""
Stable Diffusion LoRA 高效训练脚本
支持 Style1 和 Style2 两套风格的独立或联合训练
"""
import os
import sys
import argparse
import logging
from pathlib import Path
from datetime import datetime

import torch
from PIL import Image
import numpy as np

from diffusers import StableDiffusionPipeline, DDPMScheduler
from transformers import CLIPTextModel, CLIPTokenizer
from peft import LoraConfig, get_peft_model, inject_adapter_in_model
from diffusers.optimization import get_scheduler
from torch.utils.data import Dataset, DataLoader
from accelerate import Accelerator
from tqdm.auto import tqdm
import gc

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


class ImageDataset(Dataset):
    """训练图片数据集 - 支持两类风格"""

    def __init__(
        self,
        data_dir: str,
        resolution: int = 512,
        style1_dir: str = None,
        style2_dir: str = None,
        style1_trigger: str = "STYLE1",
        style2_trigger: str = "STYLE2",
        style1_caption_dir: str = None,
        style2_caption_dir: str = None,
    ):
        self.resolution = resolution
        self.style1_trigger = style1_trigger
        self.style2_trigger = style2_trigger

        self.samples = []

        if style1_dir and Path(style1_dir).exists():
            style1_path = Path(style1_dir)
            style1_captions = self._load_captions(style1_caption_dir) if style1_caption_dir else {}
            for img_file in style1_path.iterdir():
                if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                    caption = style1_captions.get(img_file.stem, "")
                    self.samples.append((str(img_file), style1_trigger, caption))

        if style2_dir and Path(style2_dir).exists():
            style2_path = Path(style2_dir)
            style2_captions = self._load_captions(style2_caption_dir) if style2_caption_dir else {}
            for img_file in style2_path.iterdir():
                if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                    caption = style2_captions.get(img_file.stem, "")
                    self.samples.append((str(img_file), style2_trigger, caption))

        if not self.samples and Path(data_dir).exists():
            data_path = Path(data_dir)
            for img_file in data_path.iterdir():
                if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                    self.samples.append((str(img_file), style1_trigger, ""))

        if not self.samples:
            raise ValueError(f"No images found in {data_dir}")

        logger.info(f"Found {len(self.samples)} training images")
        style1_count = sum(1 for _, t, _ in self.samples if t == style1_trigger)
        style2_count = sum(1 for _, t, _ in self.samples if t == style2_trigger)
        logger.info(f"  {style1_trigger}: {style1_count} images")
        logger.info(f"  {style2_trigger}: {style2_count} images")

    def _load_captions(self, caption_dir: str):
        captions = {}
        if caption_dir and Path(caption_dir).exists():
            for cap_file in Path(caption_dir).iterdir():
                if cap_file.suffix == ".txt":
                    with open(cap_file, "r", encoding="utf-8") as f:
                        captions[cap_file.stem] = f.read().strip()
        return captions

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        image_path, trigger, caption = self.samples[idx]

        image = Image.open(image_path).convert("RGB")
        if image.size[0] != self.resolution or image.size[1] != self.resolution:
            image = image.resize((self.resolution, self.resolution), Image.LANCZOS)

        image = np.array(image).astype(np.float32) / 127.5 - 1.0
        image = np.transpose(image, (2, 0, 1))

        if caption:
            prompt = f"a photo of {trigger} {caption}"
        else:
            prompt = f"a photo of {trigger}"

        return torch.from_numpy(image), prompt, trigger


def load_models(model_id: str, mixed_precision: str = "fp16"):
    """加载 Stable Diffusion 模型"""
    logger.info(f"Loading model: {model_id}")

    torch_dtype = torch.float16 if mixed_precision == "fp16" else torch.float32

    pipeline = StableDiffusionPipeline.from_pretrained(
        model_id,
        torch_dtype=torch_dtype,
        safety_checker=None,
        requires_safety_checker=False,
    )

    return pipeline


def prepare_lora_model(pipeline, lora_rank: int = 4, lora_alpha: int = 4):
    """为 UNet 添加 LoRA"""
    logger.info("Setting up LoRA...")

    lora_config = LoraConfig(
        r=lora_rank,
        lora_alpha=lora_alpha,
        lora_dropout=0.0,
        target_modules=["to_q", "to_k", "to_v", "to_out.0"],
        bias="none",
        inference_mode=False,
    )

    # 使用 inject_adapter_in_model 替代 get_peft_model
    pipeline.unet = inject_adapter_in_model(lora_config, pipeline.unet)
    
    # 打印可训练参数
    trainable_params = 0
    all_params = 0
    for _, param in pipeline.unet.named_parameters():
        all_params += param.numel()
        if param.requires_grad:
            trainable_params += param.numel()
    logger.info(f"Trainable params: {trainable_params:,} / {all_params:,} ({100 * trainable_params / all_params:.2f}%)")

    return pipeline


def train_lora(
    model_id: str,
    output_dir: str,
    style: str = "both",
    lora_rank: int = 4,
    lora_alpha: int = 4,
    num_epochs: int = 15,
    batch_size: int = 1,
    learning_rate: float = 1e-4,
    resolution: int = 512,
    mixed_precision: str = "fp16",
    style1_trigger: str = "STYLE1",
    style2_trigger: str = "STYLE2",
    style1_dir: str = "data/images/style1",
    style2_dir: str = "data/images/style2",
):
    """训练 LoRA 模型"""

    accelerator = Accelerator(
        gradient_accumulation_steps=1,
        mixed_precision=mixed_precision,
        log_with="tensorboard",
        project_dir=output_dir,
    )

    style1_path = Path(style1_dir)
    style2_path = Path(style2_dir)

    if style == "style1":
        if not style1_path.exists():
            raise ValueError(f"Style1 directory not found: {style1_dir}")
        train_dataset = ImageDataset(
            data_dir=str(style1_path.parent),
            resolution=resolution,
            style1_dir=str(style1_path),
            style1_trigger=style1_trigger,
        )
    elif style == "style2":
        if not style2_path.exists():
            raise ValueError(f"Style2 directory not found: {style2_dir}")
        train_dataset = ImageDataset(
            data_dir=str(style2_path.parent),
            resolution=resolution,
            style2_dir=str(style2_path),
            style2_trigger=style2_trigger,
        )
    else:
        train_dataset = ImageDataset(
            data_dir="data/images",
            resolution=resolution,
            style1_dir=str(style1_path) if style1_path.exists() else None,
            style2_dir=str(style2_path) if style2_path.exists() else None,
            style1_trigger=style1_trigger,
            style2_trigger=style2_trigger,
        )

    train_dataloader = DataLoader(
        train_dataset,
        batch_size=batch_size,
        shuffle=True,
        num_workers=0,
    )

    pipeline = load_models(model_id, mixed_precision)
    pipeline = prepare_lora_model(pipeline, lora_rank, lora_alpha)

    text_encoder = pipeline.text_encoder
    tokenizer = pipeline.tokenizer

    optimizer = torch.optim.AdamW(
        pipeline.unet.parameters(),
        lr=learning_rate,
        weight_decay=0.01,
    )

    lr_scheduler = get_scheduler(
        "constant",
        optimizer=optimizer,
        num_warmup_steps=0,
        num_training_steps=len(train_dataloader) * num_epochs,
    )

    pipeline.unet, optimizer, train_dataloader, lr_scheduler = accelerator.prepare(
        pipeline.unet, optimizer, train_dataloader, lr_scheduler
    )

    device = accelerator.device
    pipeline = pipeline.to(device)
    text_encoder = text_encoder.to(device)

    if torch.cuda.is_available():
        torch.backends.cuda.matmul.allow_tf32 = True

    global_step = 0
    progress_bar = tqdm(total=num_epochs * len(train_dataloader), desc="Training")

    for epoch in range(num_epochs):
        pipeline.unet.train()

        epoch_losses = []
        for batch_idx, (images, prompts, triggers) in enumerate(train_dataloader):
            with accelerator.accumulate(pipeline.unet):
                text_inputs = tokenizer(
                    prompts,
                    padding="max_length",
                    max_length=tokenizer.model_max_length,
                    truncation=True,
                    return_tensors="pt",
                )

                text_input_ids = text_inputs.input_ids.to(device)
                attention_mask = text_inputs.attention_mask.to(device)

                with torch.no_grad():
                    text_embeds = text_encoder(text_input_ids, attention_mask=attention_mask)[0]

                images = images.to(device)
                images = images.to(pipeline.vae.dtype)
                latents = pipeline.vae.encode(images).latent_dist.sample()
                latents = latents * pipeline.vae.config.scaling_factor

                noise = torch.randn_like(latents)
                timesteps = torch.randint(
                    0,
                    pipeline.scheduler.config.num_train_timesteps,
                    (latents.shape[0],),
                    device=latents.device,
                ).long()

                noisy_latents = pipeline.scheduler.add_noise(latents, noise, timesteps)

                model_pred = pipeline.unet(
                    noisy_latents, timesteps, encoder_hidden_states=text_embeds
                ).sample

                loss = torch.nn.functional.mse_loss(model_pred, noise, reduction="mean")

                accelerator.backward(loss)
                accelerator.clip_grad_norm_(pipeline.unet.parameters(), 1.0)
                optimizer.step()
                lr_scheduler.step()
                optimizer.zero_grad()

                epoch_losses.append(loss.item())

            if accelerator.sync_gradients:
                global_step += 1
                progress_bar.update(1)

        avg_loss = sum(epoch_losses) / len(epoch_losses)
        progress_bar.set_postfix({"epoch": epoch + 1, "loss": f"{avg_loss:.4f}"})

        if (epoch + 1) % 5 == 0 or epoch == num_epochs - 1:
            save_lora(pipeline, output_dir, f"epoch-{epoch + 1}", style, style1_trigger, style2_trigger)

    progress_bar.close()
    save_lora(pipeline, output_dir, "final", style, style1_trigger, style2_trigger)
    logger.info("Training complete!")
    logger.info(f"LoRA model saved to: {output_dir}")

    return output_dir


def save_lora(pipeline, output_dir: str, suffix: str, style: str, style1_trigger: str, style2_trigger: str):
    """保存 LoRA 权重"""
    output_path = Path(output_dir)

    if style == "style1":
        final_path = output_path / "style1" / suffix
    elif style == "style2":
        final_path = output_path / "style2" / suffix
    else:
        final_path = output_path / "both" / suffix

    final_path.mkdir(parents=True, exist_ok=True)
    pipeline.unet.save_pretrained(final_path)

    with open(final_path / "metadata.txt", "w") as f:
        f.write(f"style={style}\n")
        f.write(f"trigger_style1={style1_trigger}\n")
        f.write(f"trigger_style2={style2_trigger}\n")

    logger.info(f"Saved LoRA to {final_path}")


def main():
    parser = argparse.ArgumentParser(description="Stable Diffusion LoRA Training")

    parser.add_argument("--model_id", type=str, default="./stable-diffusion-v1-5", help="Base model path")
    parser.add_argument("--output_dir", type=str, default="output/lora", help="Output directory")

    parser.add_argument(
        "--style",
        type=str,
        default="both",
        choices=["style1", "style2", "both"],
        help="Style to train: style1, style2, or both",
    )

    parser.add_argument("--lora_rank", type=int, default=4, help="LoRA rank")
    parser.add_argument("--lora_alpha", type=int, default=4, help="LoRA alpha")
    parser.add_argument("--num_epochs", type=int, default=15, help="Number of epochs")
    parser.add_argument("--batch_size", type=int, default=1, help="Batch size")
    parser.add_argument("--learning_rate", type=float, default=1e-4, help="Learning rate")
    parser.add_argument("--resolution", type=int, default=512, help="Image resolution")

    parser.add_argument("--style1_trigger", type=str, default="STYLE1", help="Style1 trigger word")
    parser.add_argument("--style2_trigger", type=str, default="STYLE2", help="Style2 trigger word")
    parser.add_argument("--style1_dir", type=str, default="data/images/style1", help="Style1 images directory")
    parser.add_argument("--style2_dir", type=str, default="data/images/style2", help="Style2 images directory")

    args = parser.parse_args()

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_output_dir = f"{args.output_dir}/{args.style}_{timestamp}"

    Path(run_output_dir).mkdir(parents=True, exist_ok=True)

    logger.info("=" * 60)
    logger.info("LoRA Training Configuration:")
    logger.info(f"  Model: {args.model_id}")
    logger.info(f"  Style: {args.style}")
    logger.info(f"  LoRA Rank: {args.lora_rank}")
    logger.info(f"  Epochs: {args.num_epochs}")
    logger.info(f"  Batch Size: {args.batch_size}")
    logger.info(f"  Learning Rate: {args.learning_rate}")
    logger.info(f"  Resolution: {args.resolution}")
    logger.info("=" * 60)

    train_lora(
        model_id=args.model_id,
        output_dir=run_output_dir,
        style=args.style,
        lora_rank=args.lora_rank,
        lora_alpha=args.lora_alpha,
        num_epochs=args.num_epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        resolution=args.resolution,
        mixed_precision="fp16",
        style1_trigger=args.style1_trigger,
        style2_trigger=args.style2_trigger,
        style1_dir=args.style1_dir,
        style2_dir=args.style2_dir,
    )


if __name__ == "__main__":
    main()
