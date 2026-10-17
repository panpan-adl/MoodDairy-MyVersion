$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot '../../start_chat_backend.ps1'
if (-not (Test-Path -LiteralPath $launcher)) { $launcher = Join-Path $PSScriptRoot 'tools/windows/start_chat_backend.ps1' }
& $launcher @args
