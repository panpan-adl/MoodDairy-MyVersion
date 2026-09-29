# Stable Diffusion LoRA Fine-tuning

基于 diffusers 和 PEFT 库对 Stable Diffusion v1.5 进行 LoRA 微调。

## 环境准备

### 1. 创建虚拟环境

```bash
# 使用 conda
conda create -n sd_lora python=3.10
conda activate sd_lora

# 或使用 venv
python -m venv venv
# Windows
venv\Scripts\activate
# Linux/Mac
source venv/bin/activate
```

### 2. 安装依赖

```bash
pip install -r requirements.txt
```

### 3. 准备训练数据

#### 方式一：单风格训练

将你的训练图片放入 `data/images` 目录：

```
stablediffusion/
├── data/
│   └── images/
│       ├── image1.jpg
│       ├── image2.png
│       └── ...
├── config.py
├── train_lora.py
├── inference.py
└── requirements.txt
```

支持的图片格式: `.jpg`, `.jpeg`, `.png`, `.webp`, `.bmp`

**提示**: 图片文件名可以用作提示词前缀，例如 `mycat_001.jpg` 可以生成关于猫的 LoRA。

#### 方式二：两类风格训练（推荐）

如果你想训练两类不同的风格，使用以下目录结构：

```
data/
├── images/
│   ├── style1/          # 风格1的图片
│   │   ├── image1.jpg
│   │   ├── image2.jpg
│   │   └── ...
│   └── style2/          # 风格2的图片
│       ├── image1.jpg
│       ├── image2.jpg
│       └── ...
└── captions/            # 可选：图片描述
    ├── style1/
    │   ├── image1.txt   # 内容如: "a beautiful sunset"
    │   └── ...
    └── style2/
        ├── image1.txt
        └── ...
```

**触发词**：
- 风格1：**STYLE1**
- 风格2：**STYLE2**

可以在 `config.py` 中修改触发词。

## 训练

### 基本训练（单风格）

```bash
python train_lora.py
```

### 两类风格训练

如果想训练两类风格，将图片分别放入 `data/images/style1/` 和 `data/images/style2/` 目录，然后运行训练：

```bash
python train_lora.py
```

训练时会自动识别两个目录，使用不同的触发词：
- `data/images/style1/` → 触发词 **STYLE1**
- `data/images/style2/` → 触发词 **STYLE2**

### 自定义参数

可以在 `config.py` 中修改配置，或通过命令行参数：

```bash
# 修改训练数据目录
python train_lora.py "path/to/your/images"

# 修改输出目录
python train_lora.py "data/images" "output/my_lora"
```

### 主要配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `lora_rank` | 4 | LoRA 维度，越大模型越大但可能效果更好 |
| `num_train_epochs` | 10 | 训练轮数 |
| `learning_rate` | 1e-4 | 学习率 |
| `per_device_train_batch_size` | 1 | 每设备 batch 大小 |
| `resolution` | 512 | 图片分辨率 |

**注意**: 如果显存不足，可以减小 `per_device_train_batch_size` 或增加 `gradient_accumulation_steps`。

## 推理

训练完成后，使用 `inference.py` 加载 LoRA 生成图片：

### 基本使用

```bash
python inference.py --prompt "a photo of your_subject"
```

### 使用两类风格

#### 生成 STYLE1 风格图片

```bash
python inference.py --style style1 --prompt "a cat on the beach"
```

#### 生成 STYLE2 风格图片

```bash
python inference.py --style style2 --prompt "a cat on the beach"
```

#### 同时生成两种风格

```bash
python inference.py --style both --prompt "a cat on the beach"
```

这会生成两张图片，一张用 STYLE1，一张用 STYLE2。

### 指定 LoRA 路径

```bash
python inference.py \
    --lora_path "output/lora/final" \
    --prompt "a photo of your_subject"
```

### 完整参数示例

```bash
python inference.py \
    --lora_path "output/lora/final" \
    --prompt "a photo of your_subject sitting on a chair" \
    --negative_prompt "blurry, low quality, distorted" \
    --num_steps 30 \
    --guidance_scale 7.5 \
    --seed 42 \
    --num_images 4 \
    --output_dir "output/images"
```

### 推理参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--prompt` | 必填 | 生成图片的提示词 |
| `--style` | style1 | 风格选择: style1, style2, both |
| `--negative_prompt` | "blurry, low quality" | 负面提示词 |
| `--num_steps` | 30 | 推理步数，越高越精细但更慢 |
| `--guidance_scale` | 7.5 | 引导强度，7-10 是常用值 |
| `--seed` | None | 随机种子，设为具体数值可复现 |
| `--num_images` | 1 | 每个提示词生成几张图 |
| `--height/--width` | 512 | 输出图片尺寸 |

## 输出结构

```
output/
├── lora/
│   ├── checkpoint-100/
│   ├── checkpoint-200/
│   ├── epoch-1/
│   ├── epoch-2/
│   └── final/           # 最终模型
└── inference/           # 推理输出
```

## 显存优化

如果遇到 OOM (Out of Memory) 错误：

1. 减小 `per_device_train_batch_size` 到 1
2. 启用 gradient checkpointing
3. 使用 `enable_sequential_cpu_offload()`
4. 推理时使用 `enable_attention_slicing()`

## 常见问题

### Q: 训练需要多少显存？
A: 约 12GB 显存可以运行基本训练，更高配置可以更快。

### Q: 训练多少轮合适？
A: 通常 5-20 轮即可看到效果，过度训练可能导致过拟合。

### Q: 如何使用自定义提示词？
A: 当前版本使用空提示词训练，生产环境建议准备 caption 文件。

## 注意事项

1. 训练图片越多越好，建议至少 10-20 张
2. 图片应该包含你希望模型学习的主体
3. LoRA 训练是轻量级的，但需要适当的调参才能达到最佳效果
