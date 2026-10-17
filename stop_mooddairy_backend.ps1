$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot '../../stop_chat_backend.ps1'
if (-not (Test-Path -LiteralPath $launcher)) { $launcher = Join-Path $PSScriptRoot 'tools/windows/stop_chat_backend.ps1' }
& $launcher @args
