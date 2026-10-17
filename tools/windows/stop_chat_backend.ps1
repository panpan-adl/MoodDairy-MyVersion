param([ValidateRange(1, 120)][int]$TimeoutSec = 35)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../MoodDairy/backend'))
$logRoot = Join-Path $backendRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$worker = Join-Path $PSScriptRoot '../../start_backend_worker.py'
$managers = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -and
    $_.CommandLine.Contains('start_backend_worker.py') -and $_.CommandLine.Contains($backendRoot)
})
if ($managers.Count) {
    $statePath = Join-Path $logRoot 'backend-supervisor.json'
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    foreach ($manager in $managers) {
        if ($state.pid -ne $manager.ProcessId) { Stop-Process -Id $manager.ProcessId -ErrorAction Stop }
    }
    Set-Content -LiteralPath (Join-Path $logRoot 'backend.stop') -Value 'explicit stop' -Encoding Ascii
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $running = @($managers | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
        if (-not $running.Count) { break }
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSec)
    if ($running.Count) { throw 'Backend manager did not stop in time; inspect backend-supervisor.log.' }
}
$scripts = @((Join-Path $backendRoot 'run.py'), (Join-Path $backendRoot 'scripts/memory_worker.py'))
$owned = @(Get-CimInstance Win32_Process | Where-Object {
    $command = $_.CommandLine
    $_.Name -match '^python(w)?\.exe$' -and $command -and
    @($scripts | Where-Object { $command.Contains($_) }).Count -gt 0
})
foreach ($process in $owned) { Stop-Process -Id $process.ProcessId -ErrorAction Stop }
Write-Host 'Project API manager, API and renderer stopped. Other services were left running.'
