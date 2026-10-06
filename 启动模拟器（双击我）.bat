@echo off
chcp 65001 >nul
echo Starting MoodDairy emulator, please wait 30-60 seconds...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_emulator.ps1"
pause
