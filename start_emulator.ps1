# Start MoodDairy Android emulator
# Uses English junction paths (C:\AndroidSDK, C:\AndroidHome) on purpose:
# QEMU/WHPX freezes when the AVD lives under a Chinese-character path.

$ErrorActionPreference = "SilentlyContinue"

$env:ANDROID_USER_HOME = "C:\AndroidHome"
$env:ANDROID_AVD_HOME  = "C:\AndroidHome\avd"
$env:ANDROID_SDK_ROOT  = "C:\AndroidSDK"
$env:ANDROID_HOME      = "C:\AndroidSDK"

# Remove stale lock files
Get-ChildItem "C:\AndroidHome\avd\mooddairy.avd" -Filter "*.lock" -Force |
    Remove-Item -Force -Recurse -ErrorAction SilentlyContinue

Write-Host "Starting MoodDairy emulator, please wait 30-60 seconds..." -ForegroundColor Cyan
& "C:\AndroidSDK\emulator\emulator.exe" -avd mooddairy -no-snapshot -gpu swiftshader_indirect -scale 1.5

Write-Host "Emulator closed." -ForegroundColor Yellow
