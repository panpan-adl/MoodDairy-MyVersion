@echo off
cd /d "D:\A 数媒项目\MoodDairy-MyVersion\MoodDairy\backend"
".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000
