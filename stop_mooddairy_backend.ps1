# Stop MoodDairy backend + PostgreSQL. Saved data is not affected.
$be = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like "*uvicorn*app.main*" }
if ($be) {
    $be | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Write-Host "Backend stopped."
} else {
    Write-Host "Backend was not running."
}
Start-Process "D:\PostgreSQL\pgsql\bin\pg_ctl.exe" -ArgumentList ' -D "D:\PostgreSQL\data" stop' -WindowStyle Hidden -Wait
Write-Host "PostgreSQL stopped."
