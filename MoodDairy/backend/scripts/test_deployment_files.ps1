# ============================================================
# 任务 12 部署准备文件验证脚本
# 版本: 1.0
# 日期: 2026-01-23
# ============================================================

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "任务 12: 部署准备 - 文件验证测试" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

$testsPassed = 0
$testsFailed = 0

# 测试 1: 数据库迁移脚本
Write-Host "测试 1: 数据库迁移脚本" -ForegroundColor Yellow
if (Test-Path "migrations\001_add_media_files_table.sql") {
    $content = Get-Content "migrations\001_add_media_files_table.sql" -Raw
    if ($content -match "CREATE TABLE.*media_files" -and 
        $content -match "CREATE INDEX.*idx_media_files_diary_id" -and
        $content -match "CREATE INDEX.*idx_media_files_type") {
        Write-Host "  ✓ 迁移脚本存在且包含所有必要的 SQL 语句" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ 迁移脚本内容不完整" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 迁移脚本不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 2: 部署指南
Write-Host "`n测试 2: 部署指南文档" -ForegroundColor Yellow
if (Test-Path "migrations\DEPLOYMENT_GUIDE.md") {
    $lines = (Get-Content "migrations\DEPLOYMENT_GUIDE.md").Count
    $size = (Get-Item "migrations\DEPLOYMENT_GUIDE.md").Length
    if ($lines -gt 500 -and $size -gt 10000) {
        Write-Host "  ✓ 部署指南存在 ($lines 行, $([math]::Round($size/1024, 2)) KB)" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ 部署指南内容过少 ($lines 行)" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 部署指南不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 3: 回滚指南
Write-Host "`n测试 3: 回滚指南文档" -ForegroundColor Yellow
if (Test-Path "migrations\ROLLBACK_GUIDE.md") {
    $lines = (Get-Content "migrations\ROLLBACK_GUIDE.md").Count
    $size = (Get-Item "migrations\ROLLBACK_GUIDE.md").Length
    if ($lines -gt 400 -and $size -gt 10000) {
        Write-Host "  ✓ 回滚指南存在 ($lines 行, $([math]::Round($size/1024, 2)) KB)" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ 回滚指南内容过少 ($lines 行)" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 回滚指南不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 4: 数据库回滚脚本
Write-Host "`n测试 4: 数据库回滚脚本" -ForegroundColor Yellow
if (Test-Path "migrations\001_rollback_media_files_table.sql") {
    $content = Get-Content "migrations\001_rollback_media_files_table.sql" -Raw
    if ($content -match "DROP TABLE.*media_files" -and 
        $content -match "DROP INDEX.*idx_media_files" -and
        $content -match "BEGIN" -and
        $content -match "COMMIT") {
        Write-Host "  ✓ 回滚脚本存在且包含所有必要的 SQL 语句" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ 回滚脚本内容不完整" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 回滚脚本不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 5: 一键回滚脚本
Write-Host "`n测试 5: 一键回滚脚本" -ForegroundColor Yellow
if (Test-Path "scripts\rollback_oss.sh") {
    $content = Get-Content "scripts\rollback_oss.sh" -Raw
    if ($content -match "#!/bin/bash" -and 
        $content -match "OSS_ENABLED=false" -and
        $content -match "supervisorctl restart") {
        Write-Host "  ✓ 回滚脚本存在且包含所有必要的逻辑" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ 回滚脚本内容不完整" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 回滚脚本不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 6: 任务总结文档
Write-Host "`n测试 6: 任务总结文档" -ForegroundColor Yellow
if (Test-Path "..\.kiro\specs\media-oss-migration\TASK_12_DEPLOYMENT_SUMMARY.md") {
    Write-Host "  ✓ 任务总结文档存在" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  ✗ 任务总结文档不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 7: 文件权限（仅在 Linux/Mac 上有意义，Windows 跳过）
Write-Host "`n测试 7: 脚本文件格式" -ForegroundColor Yellow
if (Test-Path "scripts\rollback_oss.sh") {
    $firstLine = Get-Content "scripts\rollback_oss.sh" -TotalCount 1
    if ($firstLine -match "^#!/bin/bash") {
        Write-Host "  ✓ Bash 脚本格式正确" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "  ✗ Bash 脚本格式错误" -ForegroundColor Red
        $testsFailed++
    }
} else {
    Write-Host "  ✗ 脚本文件不存在" -ForegroundColor Red
    $testsFailed++
}

# 测试 8: SQL 脚本语法基本检查
Write-Host "`n测试 8: SQL 脚本语法检查" -ForegroundColor Yellow
$sqlFiles = @(
    "migrations\001_add_media_files_table.sql",
    "migrations\001_rollback_media_files_table.sql"
)
$sqlTestsPassed = 0
foreach ($sqlFile in $sqlFiles) {
    if (Test-Path $sqlFile) {
        $content = Get-Content $sqlFile -Raw
        # 检查基本 SQL 语法
        if ($content -match ";" -and 
            -not ($content -match "syntax error" -or $content -match "ERROR")) {
            $sqlTestsPassed++
        }
    }
}
if ($sqlTestsPassed -eq $sqlFiles.Count) {
    Write-Host "  ✓ 所有 SQL 脚本语法检查通过" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  ✗ 部分 SQL 脚本可能有语法问题" -ForegroundColor Red
    $testsFailed++
}

# 显示测试结果
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "测试结果汇总" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "通过: $testsPassed" -ForegroundColor Green
Write-Host "失败: $testsFailed" -ForegroundColor $(if ($testsFailed -eq 0) { "Green" } else { "Red" })
Write-Host "总计: $($testsPassed + $testsFailed)" -ForegroundColor Cyan

if ($testsFailed -eq 0) {
    Write-Host "`n✓ 所有测试通过！任务 12 部署准备文件验证成功。" -ForegroundColor Green
    Write-Host "`n文件清单:" -ForegroundColor Cyan
    Write-Host "  1. migrations\001_add_media_files_table.sql - 数据库迁移脚本" -ForegroundColor White
    Write-Host "  2. migrations\DEPLOYMENT_GUIDE.md - 部署指南" -ForegroundColor White
    Write-Host "  3. migrations\ROLLBACK_GUIDE.md - 回滚指南" -ForegroundColor White
    Write-Host "  4. migrations\001_rollback_media_files_table.sql - 数据库回滚脚本" -ForegroundColor White
    Write-Host "  5. scripts\rollback_oss.sh - 一键回滚脚本" -ForegroundColor White
    Write-Host "  6. ..\.kiro\specs\media-oss-migration\TASK_12_DEPLOYMENT_SUMMARY.md - 任务总结" -ForegroundColor White
    
    Write-Host "`n后续步骤:" -ForegroundColor Cyan
    Write-Host "  1. 阅读部署指南: cat migrations\DEPLOYMENT_GUIDE.md" -ForegroundColor White
    Write-Host "  2. 准备 OSS 配置（参考部署指南第 1 节）" -ForegroundColor White
    Write-Host "  3. 在测试环境执行部署流程" -ForegroundColor White
    Write-Host "  4. 验证部署成功后再部署到生产环境" -ForegroundColor White
    
    exit 0
} else {
    Write-Host "`n✗ 部分测试失败，请检查上述错误信息。" -ForegroundColor Red
    exit 1
}
