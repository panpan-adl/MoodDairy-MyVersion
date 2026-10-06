# MoodDairy (xin yu) one-click start: PostgreSQL + FastAPI backend + Android emulator
# Generated 2026-10-02. All components live on D: drive.

$ErrorActionPreference = "SilentlyContinue"
$backend = Join-Path $PSScriptRoot "MoodDairy\backend"

Write-Host "=== [1/4] Starting PostgreSQL (port 5432) ===" -ForegroundColor Cyan
if (-not (Get-NetTCPConnection -State Listen -LocalPort 5432)) {
    Start-Process "D:\PostgreSQL\pgsql\bin\pg_ctl.exe" `
        -ArgumentList ' -D "D:\PostgreSQL\data" -l "D:\PostgreSQL\pg.log" -o "-p 5432" start' `
        -WindowStyle Hidden -Wait
    # 等数据库真正就绪（最多30秒），而不是固定睡8秒
    $pgReady = $false
    foreach ($i in 1..30) {
        if (Get-NetTCPConnection -State Listen -LocalPort 5432 -ErrorAction SilentlyContinue) { $pgReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if ($pgReady) { Write-Host "PostgreSQL started." } else { Write-Host "PostgreSQL NOT ready yet!" -ForegroundColor Red }
} else {
    Write-Host "PostgreSQL already running."
}

Write-Host "=== [2/4] Starting backend uvicorn (port 8000) ===" -ForegroundColor Cyan
# 端口没在监听 = 后端没在真正服务。清掉残留/卡死的 uvicorn（含非本项目的）再启动
if (-not (Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue)) {
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
        Where-Object { $_.CommandLine -like "*uvicorn*app.main*" } |
        ForEach-Object {
            Write-Host ("清理卡死的旧后端进程 PID " + $_.ProcessId)
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    Start-Sleep -Seconds 2
}
$be = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*uvicorn*app.main*" }
if (-not $be) {
    Start-Process -FilePath (Join-Path $backend ".venv\Scripts\python.exe") `
        -ArgumentList '-m','uvicorn','app.main:app','--host','0.0.0.0','--port','8000' `
        -WorkingDirectory $backend
    # 轮询健康检查（最多40秒），确认真正可用
    $ok = $false
    foreach ($i in 1..20) {
        Start-Sleep -Seconds 2
        try {
            $r = Invoke-RestMethod -Uri "http://127.0.0.1:8000/health" -TimeoutSec 3
            $ok = $true; break
        } catch { }
    }
    if ($ok) { Write-Host ("Backend started. Database = " + $r.database) -ForegroundColor Green }
    else { Write-Host "Backend failed to become ready; see backend console." -ForegroundColor Red }
} else {
    Write-Host "Backend already running."
}

Write-Host "=== [4/4] Starting Android emulator ===" -ForegroundColor Cyan
# English junction paths are required: QEMU/WHPX freezes under Chinese-character paths
if (-not (Get-Process -Name "qemu-system-x86_64*")) {
    $env:ANDROID_USER_HOME = "C:\AndroidHome"
    $env:ANDROID_AVD_HOME  = "C:\AndroidHome\avd"
    $env:ANDROID_SDK_ROOT  = "C:\AndroidSDK"
    $env:ANDROID_HOME      = "C:\AndroidSDK"
    Get-ChildItem "C:\AndroidHome\avd\mooddairy.avd" -Filter "*.lock" -Force |
        Remove-Item -Force -Recurse -ErrorAction SilentlyContinue
    Start-Process "C:\AndroidSDK\emulator\emulator.exe" `
        -ArgumentList '-avd','mooddairy','-no-snapshot','-gpu','swiftshader_indirect','-scale','1.5'
    Write-Host "Emulator is booting (~30s). Open the app after it appears." -ForegroundColor Green
} else {
    Write-Host "Emulator already running."
}

Write-Host ""
Write-Host "All done. You can close this window; services keep running." -ForegroundColor Green
