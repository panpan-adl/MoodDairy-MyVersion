#!/bin/bash

# 音频加载问题诊断脚本
# 使用方法: ./diagnose_audio.sh <音频URL>

echo "=========================================="
echo "音频加载问题诊断工具"
echo "=========================================="
echo ""

# 检查是否提供了 URL
if [ -z "$1" ]; then
    echo "❌ 错误: 请提供音频 URL"
    echo "使用方法: ./diagnose_audio.sh <音频URL>"
    echo ""
    echo "示例:"
    echo "  ./diagnose_audio.sh 'https://mooddiary0808.oss-cn-beijing.aliyuncs.com/media/2026/01/29/xxx.m4a'"
    exit 1
fi

AUDIO_URL="$1"

echo "📝 测试 URL: $AUDIO_URL"
echo ""

# 1. 测试 URL 是否可访问
echo "1️⃣ 测试 URL 可访问性..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$AUDIO_URL")
echo "   HTTP 状态码: $HTTP_CODE"

if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ URL 可访问"
else
    echo "   ❌ URL 不可访问 (状态码: $HTTP_CODE)"
fi
echo ""

# 2. 检查 Content-Type
echo "2️⃣ 检查 Content-Type..."
CONTENT_TYPE=$(curl -s -I "$AUDIO_URL" | grep -i "content-type" | cut -d' ' -f2- | tr -d '\r')
echo "   Content-Type: $CONTENT_TYPE"

if [[ "$CONTENT_TYPE" == audio/* ]]; then
    echo "   ✅ Content-Type 正确"
else
    echo "   ⚠️  Content-Type 可能不正确"
fi
echo ""

# 3. 检查文件大小
echo "3️⃣ 检查文件大小..."
CONTENT_LENGTH=$(curl -s -I "$AUDIO_URL" | grep -i "content-length" | cut -d' ' -f2- | tr -d '\r')
if [ -n "$CONTENT_LENGTH" ]; then
    SIZE_MB=$(echo "scale=2; $CONTENT_LENGTH / 1024 / 1024" | bc)
    echo "   文件大小: $CONTENT_LENGTH bytes ($SIZE_MB MB)"
    
    if [ "$CONTENT_LENGTH" -gt 0 ]; then
        echo "   ✅ 文件大小正常"
    else
        echo "   ❌ 文件大小为 0"
    fi
else
    echo "   ⚠️  无法获取文件大小"
fi
echo ""

# 4. 检查 Content-Disposition
echo "4️⃣ 检查 Content-Disposition..."
CONTENT_DISPOSITION=$(curl -s -I "$AUDIO_URL" | grep -i "content-disposition" | cut -d' ' -f2- | tr -d '\r')
if [ -n "$CONTENT_DISPOSITION" ]; then
    echo "   Content-Disposition: $CONTENT_DISPOSITION"
    
    if [[ "$CONTENT_DISPOSITION" == *"attachment"* ]]; then
        echo "   ⚠️  设置为 attachment，可能导致下载而非播放"
    elif [[ "$CONTENT_DISPOSITION" == *"inline"* ]]; then
        echo "   ✅ 设置为 inline，适合播放"
    fi
else
    echo "   ℹ️  未设置 Content-Disposition"
fi
echo ""

# 5. 测试下载速度
echo "5️⃣ 测试下载速度..."
echo "   正在下载前 1MB..."
DOWNLOAD_TIME=$(curl -s -o /dev/null -w "%{time_total}" -r 0-1048576 "$AUDIO_URL")
echo "   下载时间: $DOWNLOAD_TIME 秒"

if (( $(echo "$DOWNLOAD_TIME < 5" | bc -l) )); then
    echo "   ✅ 下载速度正常"
else
    echo "   ⚠️  下载速度较慢"
fi
echo ""

# 6. 检查 HTTPS 证书
echo "6️⃣ 检查 HTTPS 证书..."
if [[ "$AUDIO_URL" == https://* ]]; then
    CERT_INFO=$(curl -s -v "$AUDIO_URL" 2>&1 | grep -i "SSL certificate")
    if [ -n "$CERT_INFO" ]; then
        echo "   $CERT_INFO"
        echo "   ✅ HTTPS 证书正常"
    else
        echo "   ℹ️  无法获取证书信息"
    fi
else
    echo "   ℹ️  使用 HTTP 协议"
fi
echo ""

# 7. 尝试下载到本地
echo "7️⃣ 尝试下载到本地..."
TEMP_FILE="/tmp/audio_test.m4a"
curl -s -o "$TEMP_FILE" "$AUDIO_URL"

if [ -f "$TEMP_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$TEMP_FILE" 2>/dev/null || stat -c%s "$TEMP_FILE" 2>/dev/null)
    echo "   下载成功: $FILE_SIZE bytes"
    
    # 检查文件类型
    FILE_TYPE=$(file -b "$TEMP_FILE")
    echo "   文件类型: $FILE_TYPE"
    
    if [[ "$FILE_TYPE" == *"Audio"* ]] || [[ "$FILE_TYPE" == *"MPEG"* ]] || [[ "$FILE_TYPE" == *"ISO Media"* ]]; then
        echo "   ✅ 文件格式正确"
    else
        echo "   ⚠️  文件格式可能不正确"
    fi
    
    # 清理临时文件
    rm "$TEMP_FILE"
else
    echo "   ❌ 下载失败"
fi
echo ""

# 8. 总结
echo "=========================================="
echo "📊 诊断总结"
echo "=========================================="
echo ""

if [ "$HTTP_CODE" = "200" ] && [[ "$CONTENT_TYPE" == audio/* ]]; then
    echo "✅ URL 基本正常，可以尝试以下操作："
    echo ""
    echo "1. 查看 Android 应用日志:"
    echo "   adb logcat | grep -E 'AudioMediaItem|DiaryEditorVMDebug'"
    echo ""
    echo "2. 检查应用权限:"
    echo "   - INTERNET 权限"
    echo "   - usesCleartextTraffic (如果是 HTTP)"
    echo ""
    echo "3. 尝试在浏览器中打开 URL，看是否能播放"
    echo ""
    echo "4. 如果仍然失败，考虑使用 ExoPlayer 替代 MediaPlayer"
else
    echo "❌ URL 存在问题，请检查："
    echo ""
    if [ "$HTTP_CODE" != "200" ]; then
        echo "- HTTP 状态码不是 200"
    fi
    if [[ "$CONTENT_TYPE" != audio/* ]]; then
        echo "- Content-Type 不是音频类型"
    fi
    echo ""
    echo "建议："
    echo "1. 检查 OSS 配置"
    echo "2. 确认 Bucket 权限设置"
    echo "3. 检查 CORS 配置"
fi

echo ""
echo "=========================================="



