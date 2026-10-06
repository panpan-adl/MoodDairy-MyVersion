cd "d:\A 数媒项目\MoodDairy-MyVersion\MoodDairy\backend"
$env:PYTHONPATH = "d:\A 数媒项目\MoodDairy-MyVersion\MoodDairy\backend"
& "d:\A 数媒项目\MoodDairy-MyVersion\MoodDairy\backend\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --log-level info
