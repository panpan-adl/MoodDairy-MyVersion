# 音频加载问题诊断脚本 (Windows PowerShell)
# 使用方法: .\diagnose_audio.ps1 -Url "https://..."

param(
    [Parameter(Mandatory=$true)]
    [string]$Url
)

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "音频加载问题诊断工具" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "📝 测试 URL: $Url" -ForegroundColor White
Write-Host ""

# 1. 测试 URL 是否可访问
Write-Host "1️⃣ 测试 URL 可访问性..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri $Url -Method Head -UseBasicParsing -TimeoutSec 10
    $statusCode = $response.StatusCode
    Write-Host "   HTTP 状态码: $statusCode" -ForegroundColor White
    
    if ($statusCode -eq 200) {
        Write-Host "   ✅ URL 可访问" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️  URL 状态码异常: $statusCode" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   ❌ URL 不可访问: $($_.Exception.Message)" -ForegroundColor Red
    $statusCode = 0
}
Write-Host ""

# 2. 检查 Content-Type
Write-Host "2️⃣ 检查 Content-Type..." -ForegroundColor Yellow
try {
    $contentType = $response.Headers["Content-Type"]
    Write-Host "   Content-Type: $contentType" -ForegroundColor White
    
    if ($contentType -like "audio/*") {
        Write-Host "   ✅ Content-Type 正确" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️  Content-Type 可能不正确" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   ⚠️  无法获取 Content-Type" -ForegroundColor Yellow
}
Write-Host ""

# 3. 检查文件大小
Write-Host "3️⃣ 检查文件大小..." -ForegroundColor Yellow
try {
    $contentLength = $response.Headers["Content-Length"]
    if ($contentLength) {
        $sizeMB = [math]::Round($contentLength / 1MB, 2)
        Write-Host "   文件大小: $contentLength bytes ($sizeMB MB)" -ForegroundColor White
        
        if ($contentLength -gt 0) {
            Write-Host "   ✅ 文件大小正常" -ForegroundColor Green
        } else {
            Write-Host "   ❌ 文件大小为 0" -ForegroundColor Red
        }
    } else {
        Write-Host "   ⚠️  无法获取文件大小" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   ⚠️  无法获取文件大小" -ForegroundColor Yellow
}
Write-Host ""

# 4. 检查 Content-Disposition
Write-Host "4️⃣ 检查 Content-Disposition..." -ForegroundColor Yellow
try {
    $contentDisposition = $response.Headers["Content-Disposition"]
    if ($contentDisposition) {
        Write-Host "   Content-Disposition: $contentDisposition" -ForegroundColor White
        
        if ($contentDisposition -like "*attachment*") {
            Write-Host "   ⚠️  设置为 attachment，可能导致下载而非播放" -ForegroundColor Yellow
        } elseif ($contentDisposition -like "*inline*") {
            Write-Host "   ✅ 设置为 inline，适合播放" -ForegroundColor Green
        }
    } else {
        Write-Host "   ℹ️  未设置 Content-Disposition" -ForegroundColor Gray
    }
} catch {
    Write-Host "   ℹ️  未设置 Content-Disposition" -ForegroundColor Gray
}
Write-Host ""

# 5. 测试下载速度
Write-Host "5️⃣ 测试下载速度..." -ForegroundColor Yellow
try {
    Write-Host "   正在下载前 1MB..." -ForegroundColor Gray
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $tempFile = [System.IO.Path]::GetTempFileName()
    
    # 下载前 1MB
    $webClient = New-Object System.Net.WebClient
    $webClient.Headers.Add("Range", "bytes=0-1048576")
    $webClient.DownloadFile($Url, $tempFile)
    
    $stopwatch.Stop()
    $downloadTime = $stopwatch.Elapsed.TotalSeconds
    Write-Host "   下载时间: $([math]::Round($downloadTime, 2)) 秒" -ForegroundColor White
    
    if ($downloadTime -lt 5) {
        Write-Host "   ✅ 下载速度正常" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️  下载速度较慢" -ForegroundColor Yellow
    }
    
    Remove-Item $tempFile -ErrorAction SilentlyContinue
} catch {
    Write-Host "   ⚠️  无法测试下载速度: $($_.Exception.Message)" -ForegroundColor Yellow
}
Write-Host ""

# 6. 检查 HTTPS 证书
Write-Host "6️⃣ 检查 HTTPS 证书..." -ForegroundColor Yellow
if ($Url -like "https://*") {
    try {
        $uri = [System.Uri]$Url
        $request = [System.Net.HttpWebRequest]::Create($uri)
        $request.Method = "HEAD"
        $request.Timeout = 10000
        $response = $request.GetResponse()
        Write-Host "   ✅ HTTPS 证书正常" -ForegroundColor Green
        $response.Close()
    } catch {
        Write-Host "   ⚠️  HTTPS 证书可能有问题: $($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "   ℹ️  使用 HTTP 协议" -ForegroundColor Gray
}
Write-Host ""

# 7. 尝试完整下载
Write-Host "7️⃣ 尝试完整下载..." -ForegroundColor Yellow
try {
    $tempFile = [System.IO.Path]::GetTempFileName()
    $webClient = New-Object System.Net.WebClient
    $webClient.DownloadFile($Url, $tempFile)
    
    $fileInfo = Get-Item $tempFile
    $fileSize = $fileInfo.Length
    Write-Host "   下载成功: $fileSize bytes" -ForegroundColor White
    
    # 检查文件扩展名
    $extension = [System.IO.Path]::GetExtension($Url)
    Write-Host "   文件扩展名: $extension" -ForegroundColor White
    
    if ($extension -in @(".m4a", ".mp3", ".wav", ".ogg", ".flac")) {
        Write-Host "   ✅ 文件格式正确" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️  文件格式可能不正确" -ForegroundColor Yellow
    }
    
    Remove-Item $tempFile -ErrorAction SilentlyContinue
} catch {
    Write-Host "   ❌ 下载失败: $($_.Exception.Message)" -ForegroundColor Red
}
Write-Host ""

# 8. 总结
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "📊 诊断总结" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

if ($statusCode -eq 200 -and $contentType -like "audio/*") {
    Write-Host "✅ URL 基本正常，可以尝试以下操作：" -ForegroundColor Green
    Write-Host ""
    Write-Host "1. 查看 Android 应用日志:" -ForegroundColor White
    Write-Host "   adb logcat | Select-String -Pattern 'AudioMediaItem|DiaryEditorVMDebug'" -ForegroundColor Gray
    Write-Host ""
    Write-Host "2. 检查应用权限:" -ForegroundColor White
    Write-Host "   - INTERNET 权限" -ForegroundColor Gray
    Write-Host "   - usesCleartextTraffic (如果是 HTTP)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "3. 尝试在浏览器中打开 URL，看是否能播放" -ForegroundColor White
    Write-Host ""
    Write-Host "4. 如果仍然失败，考虑使用 ExoPlayer 替代 MediaPlayer" -ForegroundColor White
} else {
    Write-Host "❌ URL 存在问题，请检查：" -ForegroundColor Red
    Write-Host ""
    if ($statusCode -ne 200) {
        Write-Host "- HTTP 状态码不是 200" -ForegroundColor Yellow
    }
    if ($contentType -notlike "audio/*") {
        Write-Host "- Content-Type 不是音频类型" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "建议：" -ForegroundColor White
    Write-Host "1. 检查 OSS 配置" -ForegroundColor Gray
    Write-Host "2. 确认 Bucket 权限设置" -ForegroundColor Gray
    Write-Host "3. 检查 CORS 配置" -ForegroundColor Gray
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# 提供复制 URL 到浏览器的选项
Write-Host "💡 提示: 你可以在浏览器中打开这个 URL 来测试:" -ForegroundColor Cyan
Write-Host $Url -ForegroundColor White
Write-Host ""



