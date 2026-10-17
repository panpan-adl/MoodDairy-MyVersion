param([string]$PythonPath = 'D:/software/python/python.exe',
    [ValidateRange(1, 600)][int]$StartupTimeoutSec = 180)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../MoodDairy/backend'))
$worker = Join-Path $PSScriptRoot '../../start_backend_worker.py'
$logRoot = Join-Path $backendRoot 'logs'
$statePath = Join-Path $logRoot 'backend-supervisor.json'
if (-not (Test-Path -LiteralPath $PythonPath)) { $PythonPath = (Get-Command python -ErrorAction Stop).Source }
$expectedRevision = (& $PythonPath (Join-Path $backendRoot 'app/build_revision.py') | Select-Object -Last 1).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine backend source revision.' }
$hash = [Security.Cryptography.SHA256]::Create()
try {
    $workerRevision = ([BitConverter]::ToString($hash.ComputeHash([IO.File]::ReadAllBytes($worker)))).Replace('-', '').ToLowerInvariant()
    $key = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($backendRoot.ToLowerInvariant())))).Replace('-', '').Substring(0, 24)
}
finally { $hash.Dispose() }
$mutex = New-Object Threading.Mutex($false, "Local\MoodDairyBackend-$key")
$acquired = $false
try {
    try { $acquired = $mutex.WaitOne([TimeSpan]::FromSeconds($StartupTimeoutSec)) }
    catch [Threading.AbandonedMutexException] { $acquired = $true }
    if (-not $acquired) { throw 'Another backend launch is still in progress.' }
    function Get-Health {
        try { return Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 5 }
        catch { return $null }
    }
    function Get-Manager {
        return Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -and
            $_.CommandLine.Contains('start_backend_worker.py') -and $_.CommandLine.Contains($backendRoot)
        } | Select-Object -First 1
    }
    $manager = Get-Manager
    $health = Get-Health
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    if ($manager -and ($state.pid -ne $manager.ProcessId -or $state.supervisor_revision -ne $workerRevision -or
        ($health -and $health.revision -ne $expectedRevision))) {
        & (Join-Path $PSScriptRoot 'stop_chat_backend.ps1')
        $manager = $null
        $health = $null
    }
    if (-not $manager) {
        $listener = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
            $runScript = Join-Path $backendRoot 'run.py'
            if ($server.Name -notmatch '^python(w)?\.exe$' -or -not $server.CommandLine -or -not $server.CommandLine.Contains($runScript)) {
                throw 'Port 8000 belongs to an unverified service; it was left running.'
            }
        }
        # Clean up only verified legacy/orphan processes for this canonical backend.
        & (Join-Path $PSScriptRoot 'stop_chat_backend.ps1')
        New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
        $commandLine = '"{0}" -u "{1}" "{2}"' -f $PythonPath, $worker, $backendRoot
        $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
            CommandLine = $commandLine; CurrentDirectory = $backendRoot
        }
        if ($created.ReturnValue -ne 0) { throw "Backend manager launch failed: $($created.ReturnValue)" }
        $managerId = $created.ProcessId
    } else { $managerId = $manager.ProcessId }
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        foreach ($line in (& $adbCommand.Source devices)) {
            if ($line -match '^([^\s]+)\s+device$') { & $adbCommand.Source -s $Matches[1] reverse tcp:8000 tcp:8000 | Out-Null }
        }
    }
    Write-Host "Waiting for local backend (manager $managerId)..."
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $health = Get-Health
        if ($health.status -eq 'healthy' -and $health.revision -eq $expectedRevision) {
            Write-Host "Latest backend is ready: $backendRoot"
            return
        }
        if (-not (Get-Process -Id $managerId -ErrorAction SilentlyContinue)) {
            throw "Backend manager $managerId exited; inspect backend-supervisor.log."
        }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $StartupTimeoutSec)
    throw "Backend did not become ready within $StartupTimeoutSec seconds; inspect backend-supervisor.log."
} finally {
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
