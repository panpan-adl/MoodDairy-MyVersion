"""
测试音频文件 URL 是否可以从外部访问
"""
import requests
import os
from dotenv import load_dotenv

load_dotenv()

# 从环境变量获取服务器地址
SERVER_BASE_URL = os.getenv("SERVER_BASE_URL")

if not SERVER_BASE_URL:
    print("❌ SERVER_BASE_URL 未配置")
    exit(1)

print(f"服务器地址: {SERVER_BASE_URL}")

# 测试一个音频文件 URL（使用最新的录音）
test_url = f"{SERVER_BASE_URL}/media/2026/01/22/audio/443ba224-f5be-4d94-9483-910993c10ae4.m4a"

print(f"\n测试 URL: {test_url}")
print("正在请求...")

try:
    response = requests.head(test_url, timeout=10)
    print(f"\n✅ 响应状态码: {response.status_code}")
    print(f"Content-Type: {response.headers.get('Content-Type')}")
    print(f"Content-Length: {response.headers.get('Content-Length')} bytes")
    
    if response.status_code == 200:
        print("\n✅ URL 可以正常访问！百度 ASR 应该可以使用这个 URL")
    else:
        print(f"\n⚠️  URL 返回了非 200 状态码")
        
except requests.exceptions.Timeout:
    print("\n❌ 请求超时")
except requests.exceptions.ConnectionError:
    print("\n❌ 连接失败")
except Exception as e:
    print(f"\n❌ 错误: {e}")

print("\n提示：")
print("1. 如果 URL 无法访问，检查 ngrok 是否正在运行")
print("2. 确保本地服务器（端口 8000）正在运行")
print("3. 检查 ngrok 的免费版是否有访问限制")
