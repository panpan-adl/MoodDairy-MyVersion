@echo off
REM 数据库迁移脚本执行器（Windows 批处理）
REM 使用方法: apply_migrations.bat

echo ============================================================
echo 开始执行数据库迁移
echo ============================================================
echo.

REM 检查 psql 是否可用
where psql >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo 错误: 未找到 psql 命令
    echo 请安装 PostgreSQL 客户端工具并添加到 PATH
    exit /b 1
)

REM 从 .env 文件读取数据库连接信息
if exist .env (
    for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
        if "%%a"=="DATABASE_URL" set DATABASE_URL=%%b
    )
)

REM 检查 DATABASE_URL
if "%DATABASE_URL%"=="" (
    echo 错误: 未找到 DATABASE_URL 环境变量
    echo 请在 .env 文件中设置 DATABASE_URL
    exit /b 1
)

echo 找到数据库连接: %DATABASE_URL%
echo.

REM 检查 migrations 目录
if not exist migrations (
    echo 错误: migrations 目录不存在
    exit /b 1
)

REM 执行每个迁移文件
for %%f in (migrations\*.sql) do (
    echo ============================================================
    echo 执行迁移: %%~nxf
    echo ============================================================
    
    psql "%DATABASE_URL%" -f "%%f"
    
    if %ERRORLEVEL% EQU 0 (
        echo ✓ 迁移 %%~nxf 执行成功
    ) else (
        echo ✗ 迁移 %%~nxf 执行失败
        exit /b 1
    )
    echo.
)

echo ============================================================
echo 所有迁移执行完成！
echo ============================================================
pause
