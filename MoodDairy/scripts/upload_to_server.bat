@echo off
setlocal

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"

set "SERVER=121.199.40.1"
set "USER=root"
set "REMOTE_PATH=/root/Competition/"
set "BACKEND_PATH=%PROJECT_ROOT%\backend"
set "DATABASE_INIT=%PROJECT_ROOT%\database\init.sql"

echo ========================================
echo Upload backend to server
echo ========================================
echo Server: %SERVER%
echo Project root: %PROJECT_ROOT%
echo Backend path: %BACKEND_PATH%
echo Database init: %DATABASE_INIT%
echo Remote path: %REMOTE_PATH%
echo.

if not exist "%BACKEND_PATH%" (
    echo Backend path not found.
    exit /b 1
)

if not exist "%DATABASE_INIT%" (
    echo Database init script not found.
    exit /b 1
)

echo [1/2] Upload backend directory...
scp -r "%BACKEND_PATH%" %USER%@%SERVER%:%REMOTE_PATH%
if errorlevel 1 (
    echo Backend upload failed.
    exit /b 1
)

echo [2/2] Upload database init script...
scp "%DATABASE_INIT%" %USER%@%SERVER%:%REMOTE_PATH%database/
if errorlevel 1 (
    echo Database init upload failed.
    exit /b 1
)

echo.
echo Upload completed.
echo Next:
echo 1. ssh %USER%@%SERVER%
echo 2. Verify remote directories under %REMOTE_PATH%
echo 3. Start backend service on the server
echo.
pause
