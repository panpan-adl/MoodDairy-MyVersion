import os, sys, subprocess
backend = r"D:\A 数媒项目\MoodDairy-MyVersion\MoodDairy\backend"
os.chdir(backend)
subprocess.run([os.path.join(backend, ".venv", "Scripts", "python.exe"), "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"])
