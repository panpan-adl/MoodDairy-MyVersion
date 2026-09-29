"""
LoRA Training Configuration
"""
from dataclasses import dataclass
from pathlib import Path

@dataclass
class Config:
    # Model settings - 使用本地模型路径（如果已有下载好的模型）
    model_id: str = "./stable-diffusion-v1-5"  # 或 "sd-legacy/stable-diffusion-v1-5" 从HuggingFace下载
    revision: str = None
    variant: str = None  # "fp16" for float16 variant
    
    # LoRA settings
    lora_rank: int = 4
    lora_alpha: int = 4
    lora_dropout: float = 0.0
    target_modules: list = None  # None = auto-detect
    
    # Training data - 支持两类风格
    train_data_dir: str = "data/images"
    # 两类风格的触发词
    style1_trigger: str = "STYLE1"
    style2_trigger: str = "STYLE2"
    # 风格1和风格2的图片目录
    style1_dir: str = "data/images/style1"
    style2_dir: str = "data/images/style2"
    # 风格描述（可选，用于captions）
    style1_description: str = "风格1"
    style2_description: str = "风格2"
    
    resolution: int = 512
    enable_cache: bool = False
    
    # Training hyperparameters
    num_train_epochs: int = 10
    per_device_train_batch_size: int = 1
    gradient_accumulation_steps: int = 1
    learning_rate: float = 1e-4
    lr_scheduler: str = "constant"
    lr_warmup_steps: int = 0
    max_grad_norm: float = 1.0
    logging_steps: int = 10
    save_steps: int = 100
    save_total_limit: int = 2
    
    # Output settings
    output_dir: str = "output/lora"
    seed: int = 42
    mixed_precision: str = "fp16"  # "fp16", "bf16", or None
    num_workers: int = 4
    
    # Prompt settings (for training captions)
    prompt_template: str = "a photo of {trigger} {subject}"  # {trigger} will be replaced with STYLE1 or STYLE2
    
    def __post_init__(self):
        if self.target_modules is None:
            # Common LoRA target modules for SD v1.5
            self.target_modules = ["to_q", "to_k", "to_v", "to_out.0"]
    
    @property
    def train_data_path(self) -> Path:
        return Path(self.train_data_dir)
