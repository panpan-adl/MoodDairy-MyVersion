"""
Stable Diffusion LoRA Training Script
基于 diffusers 和 PEFT 库进行 LoRA 微调
"""
import os
import sys
from pathlib import Path
import torch
from PIL import Image
import numpy as np

from config import Config
from diffusers import StableDiffusionPipeline, UNet2DConditionModel
from transformers import CLIPTextModel, CLIPTokenizer
from peft import LoraConfig, get_peft_model, PeftModel
from diffusers.optimization import get_scheduler
from torch.utils.data import Dataset, DataLoader
from accelerate import Accelerator
from tqdm.auto import tqdm
import logging

# Setup logging
logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


class ImageDataset(Dataset):
    """训练图片数据集 - 支持两类风格"""
    
    def __init__(self, data_dir: str, resolution: int = 512, 
                 style1_dir: str = None, style2_dir: str = None,
                 style1_trigger: str = "STYLE1", style2_trigger: str = "STYLE2",
                 style1_caption_dir: str = None, style2_caption_dir: str = None):
        self.resolution = resolution
        self.style1_trigger = style1_trigger
        self.style2_trigger = style2_trigger
        
        # 收集所有图片和对应的触发词
        self.samples = []
        
        # 处理风格1的图片
        if style1_dir and Path(style1_dir).exists():
            style1_path = Path(style1_dir)
            style1_captions = self._load_captions(style1_caption_dir) if style1_caption_dir else {}
            for img_file in style1_path.iterdir():
                if img_file.suffix.lower() in ('.jpg', '.jpeg', '.png', '.webp', '.bmp'):
                    caption = style1_captions.get(img_file.stem, "")
                    self.samples.append((str(img_file), style1_trigger, caption))
        
        # 处理风格2的图片
        if style2_dir and Path(style2_dir).exists():
            style2_path = Path(style2_dir)
            style2_captions = self._load_captions(style2_caption_dir) if style2_caption_dir else {}
            for img_file in style2_path.iterdir():
                if img_file.suffix.lower() in ('.jpg', '.jpeg', '.png', '.webp', '.bmp'):
                    caption = style2_captions.get(img_file.stem, "")
                    self.samples.append((str(img_file), style2_trigger, caption))
        
        # 如果没有指定风格目录，则从主目录加载（兼容旧模式）
        if not self.samples and Path(data_dir).exists():
            data_path = Path(data_dir)
            for img_file in data_path.iterdir():
                if img_file.suffix.lower() in ('.jpg', '.jpeg', '.png', '.webp', '.bmp'):
                    self.samples.append((str(img_file), style1_trigger, ""))
        
        if not self.samples:
            raise ValueError(f"No images found in {data_dir}")
        
        logger.info(f"Found {len(self.samples)} training images")
        # 统计各类风格数量
        style1_count = sum(1 for _, t, _ in self.samples if t == style1_trigger)
        style2_count = sum(1 for _, t, _ in self.samples if t == style2_trigger)
        logger.info(f"  Style1 ({style1_trigger}): {style1_count} images")
        logger.info(f"  Style2 ({style2_trigger}): {style2_count} images")
    
    def _load_captions(self, caption_dir: str):
        """加载描述文件"""
        captions = {}
        if caption_dir and Path(caption_dir).exists():
            for cap_file in Path(caption_dir).iterdir():
                if cap_file.suffix == '.txt':
                    with open(cap_file, 'r', encoding='utf-8') as f:
                        captions[cap_file.stem] = f.read().strip()
        return captions
    
    def __len__(self):
        return len(self.samples)
    
    def __getitem__(self, idx):
        image_path, trigger, caption = self.samples[idx]
        
        # Load and resize image
        image = Image.open(image_path).convert("RGB")
        image = image.resize((self.resolution, self.resolution), Image.LANCZOS)
        
        # Convert to numpy array and normalize to [-1, 1]
        image = np.array(image).astype(np.float32) / 127.5 - 1.0
        
        # HWC -> CHW
        image = np.transpose(image, (2, 0, 1))
        
        # 构建提示词
        if caption:
            prompt = f"a photo of {trigger} {caption}"
        else:
            prompt = f"a photo of {trigger}"
        
        return torch.from_numpy(image), prompt, trigger


def load_models(config: Config):
    """加载 Stable Diffusion 模型"""
    logger.info(f"Loading model: {config.model_id}")
    
    # Load pipeline
    pipeline = StableDiffusionPipeline.from_pretrained(
        config.model_id,
        torch_dtype=torch.float16 if config.mixed_precision else torch.float32,
        revision=config.revision,
        variant=config.variant,
    )
    
    # Enable CPU offload if needed (for low VRAM)
    # pipeline.enable_model_cpu_offload()
    
    return pipeline


def prepare_lora_model(pipeline: StableDiffusionPipeline, config: Config):
    """为 UNet 添加 LoRA"""
    logger.info("Setting up LoRA...")
    
    # LoRA config
    lora_config = LoraConfig(
        r=config.lora_rank,
        lora_alpha=config.lora_alpha,
        lora_dropout=config.lora_dropout,
        target_modules=config.target_modules,
        bias="none",
        inference_mode=False,
    )
    
    # Apply LoRA to UNet
    pipeline.unet = get_peft_model(lora_config, pipeline.unet)
    pipeline.unet.print_trainable_parameters()
    
    return pipeline


def prepare_text_encoder(pipeline: StableDiffusionPipeline):
    """准备文本编码器"""
    return pipeline.text_encoder, pipeline.tokenizer


def training_loop(pipeline: StableDiffusionPipeline, config: Config):
    """训练循环"""
    accelerator = Accelerator(
        gradient_accumulation_steps=config.gradient_accumulation_steps,
        mixed_precision=config.mixed_precision,
        log_with="tensorboard",
        project_dir=config.output_dir,
    )
    
    # Prepare dataset
    train_dataset = ImageDataset(
        data_dir=config.train_data_dir,
        resolution=config.resolution,
        style1_dir=config.style1_dir,
        style2_dir=config.style2_dir,
        style1_trigger=config.style1_trigger,
        style2_trigger=config.style2_trigger,
        style1_caption_dir="data/captions/style1" if Path("data/captions/style1").exists() else None,
        style2_caption_dir="data/captions/style2" if Path("data/captions/style2").exists() else None,
    )
    
    train_dataloader = DataLoader(
        train_dataset,
        batch_size=config.per_device_train_batch_size,
        shuffle=True,
        num_workers=config.num_workers,
    )
    
    # 准备文本编码器
    text_encoder, tokenizer = prepare_text_encoder(pipeline)
    
    # Optimizer
    optimizer = torch.optim.AdamW(
        pipeline.unet.parameters(),
        lr=config.learning_rate,
        weight_decay=0.01,
    )
    
    # Learning rate scheduler
    lr_scheduler = get_scheduler(
        config.lr_scheduler,
        optimizer=optimizer,
        num_warmup_steps=config.lr_warmup_steps,
        num_training_steps=len(train_dataloader) * config.num_train_epochs,
    )
    
    # Prepare with accelerator
    pipeline.unet, optimizer, train_dataloader, lr_scheduler = accelerator.prepare(
        pipeline.unet, optimizer, train_dataloader, lr_scheduler
    )
    
    # Move to device
    pipeline = pipeline.to(accelerator.device)
    text_encoder = text_encoder.to(accelerator.device)
    
    # Enable tf32 for better performance on Ampere GPUs
    if torch.cuda.is_available():
        torch.backends.cuda.matmul.allow_tf32 = True
    
    # Training loop
    global_step = 0
    progress_bar = tqdm(
        total=config.num_train_epochs * len(train_dataloader),
        desc="Training",
        initial=global_step,
    )
    
    for epoch in range(config.num_train_epochs):
        pipeline.unet.train()
        
        for batch_idx, (images, prompts, triggers) in enumerate(train_dataloader):
            with accelerator.accumulate(pipeline.unet):
                # 编码文本提示词
                text_inputs = tokenizer(
                    prompts,
                    padding="max_length",
                    max_length=tokenizer.model_max_length,
                    truncation=True,
                    return_tensors="pt",
                )
                
                text_input_ids = text_inputs.input_ids
                attention_mask = text_inputs.attention_mask
                
                with torch.no_grad():
                    text_embeds = text_encoder(
                        text_input_ids.to(accelerator.device),
                        attention_mask=attention_mask.to(accelerator.device),
                    )[0]
                
                # Convert images to latent space
                images = images.to(accelerator.device)
                latents = pipeline.vae.encode(images).latent_dist.sample()
                latents = latents * pipeline.vae.config.scaling_factor
                
                # Add noise
                noise = torch.randn_like(latents)
                timesteps = torch.randint(
                    0, pipeline.scheduler.config.num_train_timesteps, 
                    (latents.shape[0],), device=latents.device
                ).long()
                
                noisy_latents = pipeline.scheduler.add_noise(latents, noise, timesteps)
                
                # Predict noise
                model_pred = pipeline.unet(
                    noisy_latents, timesteps, encoder_hidden_states=text_embeds
                ).sample
                
                # Compute loss
                loss = torch.nn.functional.mse_loss(
                    model_pred, noise, reduction="mean"
                )
                
                # Backward
                accelerator.backward(loss)
                
                # Gradient clipping
                if accelerator.sync_gradients:
                    accelerator.clip_grad_norm_(
                        pipeline.unet.parameters(), config.max_grad_norm
                    )
                
                optimizer.step()
                lr_scheduler.step()
                optimizer.zero_grad()
            
            # Update progress
            if accelerator.sync_gradients:
                global_step += 1
                progress_bar.update(1)
                
                if global_step % config.logging_steps == 0:
                    # 获取当前批次的风格统计
                    style1_count = sum(1 for t in triggers if t == config.style1_trigger)
                    style2_count = len(triggers) - style1_count
                    logger.info(f"Epoch {epoch}, Step {global_step}, Loss: {loss.item():.4f}, Style1: {style1_count}, Style2: {style2_count}")
                
                # Save checkpoint
                if global_step % config.save_steps == 0:
                    save_lora(pipeline, config, f"checkpoint-{global_step}")
        
        # Save after each epoch
        save_lora(pipeline, config, f"epoch-{epoch+1}")
    
    progress_bar.close()
    
    # Save final model
    save_lora(pipeline, config, "final")
    logger.info("Training complete!")
    
    return pipeline


def save_lora(pipeline: StableDiffusionPipeline, config: Config, suffix: str = "final"):
    """保存 LoRA 权重"""
    output_path = Path(config.output_dir) / suffix
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Save only the LoRA weights
    pipeline.unet.save_pretrained(output_path)
    logger.info(f"Saved LoRA to {output_path}")


def main():
    """主函数"""
    # Load config
    config = Config()
    
    # Override with command line args if provided
    if len(sys.argv) > 1:
        config.train_data_dir = sys.argv[1]
    if len(sys.argv) > 2:
        config.output_dir = sys.argv[2]
    
    # Verify data directory
    if not Path(config.train_data_dir).exists():
        logger.error(f"Training data directory not found: {config.train_data_dir}")
        logger.info("Please create the directory and add your training images.")
        logger.info("Example: mkdir data/images && cp your_images/* data/images/")
        sys.exit(1)
    
    # Create output directory
    Path(config.output_dir).mkdir(parents=True, exist_ok=True)
    
    # Load models
    pipeline = load_models(config)
    
    # Add LoRA
    pipeline = prepare_lora_model(pipeline, config)
    
    # Start training
    pipeline = training_loop(pipeline, config)
    
    logger.info(f"LoRA model saved to: {config.output_dir}")


if __name__ == "__main__":
    main()
