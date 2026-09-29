@echo off
chcp 65001 >nul
title Stable Diffusion LoRA 管理工具

:main_menu
cls
echo ================================================
echo    Stable Diffusion LoRA 管理工具
echo ================================================
echo.
echo  1. 训练 Style1 LoRA (20张图)
echo  2. 训练 Style2 LoRA (21张图)
echo  3. 同时训练两种风格
echo  4. 使用 Style1 LoRA 推理测试
echo  5. 使用 Style2 LoRA 推理测试
echo  6. 查看训练输出
echo  0. 退出
echo.
echo ================================================

set /p choice=请选择操作 (0-6):

if "%choice%"=="1" goto train_style1
if "%choice%"=="2" goto train_style2
if "%choice%"=="3" goto train_both
if "%choice%"=="4" goto test_style1
if "%choice%"=="5" goto test_style2
if "%choice%"=="6" goto view_output
if "%choice%"=="0" goto end
goto main_menu

:train_style1
cls
echo 开始训练 Style1 LoRA...
echo.
python train_lora_optimized.py --style style1 --num_epochs 15 --lora_rank 4
pause
goto main_menu

:train_style2
cls
echo 开始训练 Style2 LoRA...
echo.
python train_lora_optimized.py --style style2 --num_epochs 15 --lora_rank 4
pause
goto main_menu

:train_both
cls
echo 开始同时训练两种风格...
echo.
python train_lora_optimized.py --style both --num_epochs 15 --lora_rank 4
pause
goto main_menu

:test_style1
cls
echo 使用 Style1 LoRA 进行推理测试...
echo.
set /p prompt=请输入提示词 (默认: a beautiful landscape):
if "%prompt%"=="" set prompt=a beautiful landscape
python inference_optimized.py --style style1 --prompt "%prompt%" --lora_path output/lora/style1/final
pause
goto main_menu

:test_style2
cls
echo 使用 Style2 LoRA 进行推理测试...
echo.
set /p prompt=请输入提示词 (默认: a beautiful landscape):
if "%prompt%"=="" set prompt=a beautiful landscape
python inference_optimized.py --style style2 --prompt "%prompt%" --lora_path output/lora/style2/final
pause
goto main_menu

:view_output
cls
echo 训练输出目录:
echo.
if exist "output\lora\style1" (
    echo Style1 输出:
    dir /b /o-d "output\lora\style1"
    echo.
)
if exist "output\lora\style2" (
    echo Style2 输出:
    dir /b /o-d "output\lora\style2"
    echo.
)
if not exist "output\lora\style1" if not exist "output\lora\style2" (
    echo 暂无训练输出
)
echo.
pause
goto main_menu

:end
cls
echo 再见!
