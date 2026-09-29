"""
测试 ngrok URL 是否可以从外部访问（模拟百度 ASR 的请求）
"""
import requests
import os
from dotenv import load_dotenv

load_dotenv()

SERVER_BASE_URL = os.getenv("SERVER_BASE_URL")

if not SERVER_BASE_URL:
    print("❌ SERVER_BASE_URL 未配置")
    exit(1)

print(f"服务器地址: {SERVER_BASE_URL}")
print()

# 测试最新的音频文件
test_url = f"{SERVER_BASE_URL}/media/2026/01/22/audio/960e5791-0136-4248-994b-8147c044e050.m4a"

print(f"测试 URL: {test_url}")
print("=" * 80)
print()

try:
    # 模拟百度 ASR 的请求（不带浏览器 User-Agent）
    headers = {
        'User-Agent': 'BaiduASR/1.0'
    }
    
    print("1. 使用百度 ASR User-Agent 请求...")
    response = requests.get(test_url, headers=headers, timeout=10, allow_redirects=False)
    
    print(f"   状态码: {response.status_code}")
    print(f"   Content-Type: {response.headers.get('Content-Type')}")
    print(f"   Content-Length: {response.headers.get('Content-Length')}")
    
    if response.status_code == 200:
        print("   ✅ 成功！文件可以访问")
    elif response.status_code == 302 or response.status_code == 301:
        print(f"   ⚠️  重定向到: {response.headers.get('Location')}")
        print("   这可能是 ngrok 的警告页面")
    else:
        print(f"   ❌ 失败！状态码: {response.status_code}")
        print(f"   响应内容: {response.text[:200]}")
    
    print()
    print("2. 使用浏览器 User-Agent 请求...")
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    }
    response = requests.get(test_url, headers=headers, timeout=10)
    
    print(f"   状态码: {response.status_code}")
    print(f"   Content-Type: {response.headers.get('Content-Type')}")
    
    if response.status_code == 200:
        if 'audio' in response.headers.get('Content-Type', ''):
            print("   ✅ 成功！返回音频文件")
        elif 'html' in response.headers.get('Content-Type', ''):
            print("   ⚠️  返回 HTML 页面（可能是警告页面）")
            print(f"   页面内容: {response.text[:300]}")
        else:
            print(f"   ⚠️  返回了其他类型的内容")
    
except requests.exceptions.Timeout:
    print("❌ 请求超时")
except requests.exceptions.ConnectionError:
    print("❌ 连接失败")
except Exception as e:
    print(f"❌ 错误: {e}")

print()
print("=" * 80)
print("诊断建议:")
print()
print("如果看到重定向或 HTML 页面：")
print("  → ngrok 免费版有警告页面，百度 ASR 无法访问")
print("  → 解决方案：升级 ngrok 或使用其他内网穿透工具")
print()
print("如果看到 404：")
print("  → 文件不存在或路径错误")
print("  → 运行 python check_files.py 检查文件")
print()
print("如果看到 200 + 音频文件：")
print("  → URL 可以正常访问")
print("  → 问题可能在百度 ASR API 配置")
