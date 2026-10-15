param([string]$PythonPath = 'D:/software/python/python.exe')
$ErrorActionPreference = 'Stop'
$projectRoot = Join-Path $PSScriptRoot 'MoodDairy'
$backendRoot = Join-Path $projectRoot 'backend'
if (-not (Test-Path -LiteralPath $PythonPath)) { $PythonPath = (Get-Command python -ErrorAction Stop).Source }
$expectedRevision = (& $PythonPath (Join-Path $backendRoot 'app/build_revision.py') | Select-Object -Last 1).Trim()
$runScript = Join-Path $backendRoot 'run.py'
if (-not (Test-Path -LiteralPath $runScript)) { throw "Latest backend source is missing: $runScript" }
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) {
    foreach ($line in (& $adbCommand.Source devices)) {
        if ($line -match '^([^\s]+)\s+device$') { & $adbCommand.Source -s $Matches[1] reverse tcp:8000 tcp:8000 | Out-Null }
    }
}
function Test-BackendReady {
    try { $health = Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 2; return ($health.status -eq 'healthy' -and $health.revision -eq $expectedRevision) } catch { return $false }
}
$listener = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $isLatest = $server.CommandLine -and ($server.CommandLine.Contains($runScript) -or ($server.CommandLine.Contains('start_backend_worker.py') -and $server.CommandLine.Contains($backendRoot)))
    if ((Test-BackendReady) -and $isLatest) {
        Write-Host "Latest backend is ready: $backendRoot"
        return
    }
    $isDiary = $false
    try { $isDiary = (Invoke-RestMethod 'http://127.0.0.1:8000/' -TimeoutSec 3).message -eq 'Diary API' } catch {}
    if ($isDiary -and $server.Name -match '^python(w)?\.exe$' -and $server.CommandLine -match 'run\.py|start_backend_worker\.py|uvicorn.+app\.main') {
        Stop-Process -Id $server.ProcessId -ErrorAction Stop
    } else { throw 'Port 8000 is occupied by an unverified service. It was left running.' }
}
if (-not (Test-Path -LiteralPath $PythonPath)) { $PythonPath = (Get-Command python -ErrorAction Stop).Source }
$logRoot = Join-Path $backendRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$stderrPath = Join-Path $logRoot 'server.stderr.log'
$worker = Join-Path $PSScriptRoot 'start_backend_worker.py'
# A stale renderer must release its transaction before the current source starts.
$renderScript = Join-Path $backendRoot 'scripts/memory_worker.py'
foreach ($renderer in (Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -and $_.CommandLine.Contains($renderScript) })) {
    Stop-Process -Id $renderer.ProcessId -ErrorAction SilentlyContinue
}
# WMI starts the worker independently, so closing the build terminal does not stop the API.
$commandLine = '"{0}" -u "{1}" "{2}"' -f $PythonPath, $worker, $backendRoot
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine; CurrentDirectory = $backendRoot }
if ($created.ReturnValue -ne 0) { throw "Backend worker could not start (Windows error $($created.ReturnValue))." }
$deadline = (Get-Date).AddSeconds(45)
do {
    if (Test-BackendReady) { Write-Host "Latest backend is ready: $backendRoot"; return }
    if (-not (Get-Process -Id $created.ProcessId -ErrorAction SilentlyContinue)) { throw "Backend startup failed. See $stderrPath" }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
throw "Backend did not become ready. See $stderrPath"
