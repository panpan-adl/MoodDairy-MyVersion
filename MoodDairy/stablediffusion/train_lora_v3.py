"""
Stable Diffusion LoRA 高效训练脚本 v3
使用 DreamBooth 方式训练 LoRA
"""
import os
import argparse
import logging
from pathlib import Path
from tqdm.auto import tqdm

import torch
from torch.utils.data import Dataset, DataLoader
from torchvision import transforms
from PIL import Image

from diffusers import StableDiffusionPipeline
from transformers import CLIPTokenizer
from peft import LoraConfig, inject_adapter_in_model

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


class ImageDataset(Dataset):
    def __init__(self, data_dir: str, resolution: int = 512, trigger: str = "STYLE1"):
        self.resolution = resolution
        self.trigger = trigger
        self.transform = transforms.Compose([
            transforms.Resize((resolution, resolution), interpolation=transforms.InterpolationMode.LANCZOS),
            transforms.ToTensor(),
            transforms.Normalize([0.5], [0.5]),
        ])
        self.samples = []
        data_path = Path(data_dir)
        for img_file in data_path.iterdir():
            if img_file.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
                self.samples.append(str(img_file))
        logger.info(f"Found {len(self.samples)} images for {trigger}")

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        image = Image.open(self.samples[idx]).convert("RGB")
        image = self.transform(image)
        prompt = f"a photo of {self.trigger}"
        return image, prompt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model_id", type=str, default="./stable-diffusion-v1-5")
    parser.add_argument("--output_dir", type=str, default="output/lora")
    parser.add_argument("--style", type=str, default="style1", choices=["style1", "style2", "both"])
    parser.add_argument("--lora_rank", type=int, default=4)
    parser.add_argument("--num_epochs", type=int, default=15)
    parser.add_argument("--style1_dir", type=str, default="data/images/style1")
    parser.add_argument("--style2_dir", type=str, default="data/images/style2")
    parser.add_argument("--style1_trigger", type=str, default="STYLE1")
    parser.add_argument("--style2_trigger", type=str, default="STYLE2")
    args = parser.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    logger.info(f"Using device: {device}")

    # 加载模型 - 不指定 dtype，让 diffusers 自动处理
    logger.info(f"Loading model: {args.model_id}")
    pipeline = StableDiffusionPipeline.from_pretrained(
        args.model_id,
        safety_checker=None,
        requires_safety_checker=False,
    )
    pipeline.to(device)

    tokenizer = CLIPTokenizer.from_pretrained(args.model_id, subfolder="tokenizer")

    # 设置 LoRA
    lora_config = LoraConfig(
        r=args.lora_rank,
        lora_alpha=args.lora_rank,
        lora_dropout=0.0,
        target_modules=["to_q", "to_k", "to_v", "to_out.0"],
        bias="none",
        inference_mode=False,
    )

    pipeline.unet = inject_adapter_in_model(lora_config, pipeline.unet)
    pipeline.unet.train()

    optimizer = torch.optim.AdamW(pipeline.unet.parameters(), lr=1e-4, weight_decay=0.01)

    logger.info("=" * 60)
    styles_to_train = []
    if args.style == "style1" or args.style == "both":
        styles_to_train.append((args.style1_dir, args.style1_trigger))
    if args.style == "style2" or args.style == "both":
        styles_to_train.append((args.style2_dir, args.style2_trigger))

    for style_dir, trigger in styles_to_train:
        logger.info(f"Training {trigger} from {style_dir}")

        if not Path(style_dir).exists():
            logger.error(f"Directory not found: {style_dir}")
            continue

        dataset = ImageDataset(style_dir, resolution=512, trigger=trigger)
        dataloader = DataLoader(dataset, batch_size=1, shuffle=True)

        for epoch in range(args.num_epochs):
            epoch_loss = 0
            for images, prompts in tqdm(dataloader, desc=f"Epoch {epoch+1}"):
                images = images.to(device)
                prompts = list(prompts)

                # 文本编码
                text_inputs = tokenizer(
                    prompts,
                    padding="max_length",
                    max_length=tokenizer.model_max_length,
                    truncation=True,
                    return_tensors="pt",
                )
                text_input_ids = text_input_ids = text_inputs.input_ids.to(device)

                with torch.no_grad():
                    text_embeds = pipeline.text_encoder(text_input_ids)[0]

                # VAE 编码
                latents = pipeline.vae.encode(images).latent_dist.sample()
                latents = latents * pipeline.vae.config.scaling_factor

                # 加噪
                noise = torch.randn_like(latents)
                timesteps = torch.randint(0, pipeline.scheduler.config.num_train_timesteps,
                                          (latents.shape[0],), device=device).long()
                noisy_latents = pipeline.scheduler.add_noise(latents, noise, timesteps)

                # UNet 预测
                model_pred = pipeline.unet(noisy_latents, timesteps, encoder_hidden_states=text_embeds).sample

                # 损失
                loss = torch.nn.functional.mse_loss(model_pred, noise, reduction="mean")

                optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(pipeline.unet.parameters(), 1.0)
                optimizer.step()

                epoch_loss += loss.item()

            avg_loss = epoch_loss / len(dataloader)
            logger.info(f"  Epoch {epoch+1}/{args.num_epochs}, Loss: {avg_loss:.4f}")

        # 保存
        output_path = Path(args.output_dir) / args.style / "final"
        output_path.mkdir(parents=True, exist_ok=True)
        pipeline.unet.save_pretrained(output_path)
        logger.info(f"Saved LoRA to {output_path}")

    logger.info("Training complete!")


if __name__ == "__main__":
    main()
