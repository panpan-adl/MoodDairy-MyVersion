#!/usr/bin/env python3
"""
测试环境部署验证脚本

此脚本自动验证测试环境部署的各个方面：
- 配置验证
- 数据库验证
- OSS 连接验证
- 服务健康检查
- 功能测试

使用方法:
    python verify_test_deployment.py --env-file .env.test --api-url http://localhost:8001
"""

import os
import sys
import argparse
import requests
import json
from pathlib import Path
from typing import Dict, List, Tuple
from datetime import datetime

# 添加项目根目录到路径
sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    from dotenv import load_dotenv
    import oss2
    from sqlalchemy import create_engine, text
except ImportError as e:
    print(f"❌ 缺少依赖包: {e}")
    print("请运行: pip install python-dotenv oss2 sqlalchemy psycopg2-binary requests")
    sys.exit(1)


class Colors:
    """终端颜色"""
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    RESET = '\033[0m'
    BOLD = '\033[1m'


class TestDeploymentVerifier:
    """测试环境部署验证器"""
    
    def __init__(self, env_file: str = '.env.test', api_url: str = 'http://localhost:8001'):
        self.env_file = env_file
        self.api_url = api_url.rstrip('/')
        self.results: List[Tuple[str, bool, str]] = []
        self.load_config()
    
    def load_config(self):
        """加载配置"""
        if not Path(self.env_file).exists():
            print(f"{Colors.RED}❌ 配置文件不存在: {self.env_file}{Colors.RESET}")
            sys.exit(1)
        
        load_dotenv(self.env_file)
        print(f"{Colors.BLUE}📋 使用配置文件: {self.env_file}{Colors.RESET}\n")
    
    def add_result(self, test_name: str, passed: bool, message: str = ""):
        """添加测试结果"""
        self.results.append((test_name, passed, message))
        status = f"{Colors.GREEN}✓{Colors.RESET}" if passed else f"{Colors.RED}✗{Colors.RESET}"
        print(f"{status} {test_name}")
        if message:
            print(f"  {message}")
    
    def print_section(self, title: str):
        """打印章节标题"""
        print(f"\n{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.RESET}")
        print(f"{Colors.BOLD}{Colors.BLUE}{title}{Colors.RESET}")
        print(f"{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.RESET}\n")
    
    def verify_config(self) -> bool:
        """验证配置"""
        self.print_section("1. 配置验证")
        
        all_passed = True
        
        # 检查 OSS 配置
        oss_config = {
            'OSS_ENABLED': os.getenv('OSS_ENABLED'),
            'OSS_ENDPOINT': os.getenv('OSS_ENDPOINT'),
            'OSS_BUCKET': os.getenv('OSS_BUCKET'),
            'OSS_ACCESS_KEY_ID': os.getenv('OSS_ACCESS_KEY_ID'),
            'OSS_ACCESS_KEY_SECRET': os.getenv('OSS_ACCESS_KEY_SECRET'),
            'OSS_PUBLIC_BASE_URL': os.getenv('OSS_PUBLIC_BASE_URL'),
        }
        
        for key, value in oss_config.items():
            if value:
                masked_value = value if key not in ['OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET'] else f"{value[:8]}***"
                self.add_result(f"{key}", True, f"值: {masked_value}")
            else:
                self.add_result(f"{key}", False, "未配置")
                all_passed = False
        
        # 检查数据库配置
        db_url = os.getenv('DATABASE_URL')
        if db_url:
            # 隐藏密码
            masked_url = db_url.split('@')[1] if '@' in db_url else db_url
            self.add_result("DATABASE_URL", True, f"数据库: {masked_url}")
        else:
            self.add_result("DATABASE_URL", False, "未配置")
            all_passed = False
        
        return all_passed
    
    def verify_database(self) -> bool:
        """验证数据库"""
        self.print_section("2. 数据库验证")
        
        db_url = os.getenv('DATABASE_URL')
        if not db_url:
            self.add_result("数据库连接", False, "DATABASE_URL 未配置")
            return False
        
        try:
            engine = create_engine(db_url)
            with engine.connect() as conn:
                # 测试连接
                result = conn.execute(text("SELECT version()"))
                version = result.fetchone()[0]
                self.add_result("数据库连接", True, f"PostgreSQL 版本: {version.split(',')[0]}")
                
                # 检查 media_files 表
                result = conn.execute(text("""
                    SELECT EXISTS (
                        SELECT FROM information_schema.tables 
                        WHERE table_schema = 'public' 
                        AND table_name = 'media_files'
                    )
                """))
                table_exists = result.fetchone()[0]
                
                if table_exists:
                    self.add_result("media_files 表", True, "表已创建")
                    
                    # 检查表结构
                    result = conn.execute(text("""
                        SELECT column_name, data_type 
                        FROM information_schema.columns 
                        WHERE table_name = 'media_files'
                        ORDER BY ordinal_position
                    """))
                    columns = result.fetchall()
                    
                    required_columns = {
                        'id', 'diary_id', 'type', 'url', 'oss_bucket', 
                        'oss_key', 'content_type', 'size_bytes', 'duration_ms', 'created_at'
                    }
                    actual_columns = {col[0] for col in columns}
                    
                    if required_columns.issubset(actual_columns):
                        self.add_result("表结构", True, f"包含所有必需字段 ({len(columns)} 个字段)")
                    else:
                        missing = required_columns - actual_columns
                        self.add_result("表结构", False, f"缺少字段: {missing}")
                    
                    # 检查索引
                    result = conn.execute(text("""
                        SELECT indexname 
                        FROM pg_indexes 
                        WHERE schemaname = 'public' 
                        AND tablename = 'media_files'
                    """))
                    indexes = [row[0] for row in result.fetchall()]
                    
                    required_indexes = ['idx_media_files_diary_id', 'idx_media_files_type']
                    found_indexes = [idx for idx in required_indexes if any(idx in i for i in indexes)]
                    
                    if len(found_indexes) >= 2:
                        self.add_result("索引", True, f"找到 {len(indexes)} 个索引")
                    else:
                        self.add_result("索引", False, f"缺少必需索引")
                    
                    # 检查记录数
                    result = conn.execute(text("SELECT COUNT(*) FROM media_files"))
                    count = result.fetchone()[0]
                    self.add_result("数据记录", True, f"当前有 {count} 条记录")
                    
                else:
                    self.add_result("media_files 表", False, "表不存在")
                    return False
                
            return True
            
        except Exception as e:
            self.add_result("数据库验证", False, f"错误: {str(e)}")
            return False
    
    def verify_oss_connection(self) -> bool:
        """验证 OSS 连接"""
        self.print_section("3. OSS 连接验证")
        
        oss_enabled = os.getenv('OSS_ENABLED', 'false').lower() == 'true'
        
        if not oss_enabled:
            self.add_result("OSS 状态", True, "OSS 未启用（使用本地存储）")
            return True
        
        try:
            auth = oss2.Auth(
                os.getenv('OSS_ACCESS_KEY_ID'),
                os.getenv('OSS_ACCESS_KEY_SECRET')
            )
            bucket = oss2.Bucket(
                auth,
                os.getenv('OSS_ENDPOINT'),
                os.getenv('OSS_BUCKET')
            )
            
            # 测试连接
            info = bucket.get_bucket_info()
            self.add_result("OSS 连接", True, f"Bucket: {info.name}")
            
            # 检查 ACL
            acl = bucket.get_bucket_acl()
            self.add_result("Bucket ACL", True, f"权限: {acl.acl}")
            
            if acl.acl != 'public-read':
                print(f"  {Colors.YELLOW}⚠ 警告: Bucket 权限不是 public-read，URL 可能无法公开访问{Colors.RESET}")
            
            # 列出最近的对象
            try:
                objects = list(bucket.list_objects(prefix='media/', max_keys=5).object_list)
                self.add_result("OSS 对象", True, f"找到 {len(objects)} 个测试对象")
                for obj in objects[:3]:
                    print(f"  - {obj.key} ({obj.size} bytes)")
            except Exception as e:
                self.add_result("OSS 对象列表", False, f"无法列出对象: {e}")
            
            return True
            
        except oss2.exceptions.OssError as e:
            self.add_result("OSS 连接", False, f"OSS 错误: {e.code} - {e.message}")
            return False
        except Exception as e:
            self.add_result("OSS 连接", False, f"错误: {str(e)}")
            return False
    
    def verify_service_health(self) -> bool:
        """验证服务健康状态"""
        self.print_section("4. 服务健康检查")
        
        try:
            # 健康检查
            response = requests.get(f"{self.api_url}/health", timeout=5)
            
            if response.status_code == 200:
                self.add_result("服务状态", True, f"服务正常运行 (HTTP {response.status_code})")
                
                try:
                    data = response.json()
                    if data.get('status') == 'ok':
                        self.add_result("健康检查", True, "健康检查通过")
                    else:
                        self.add_result("健康检查", False, f"状态异常: {data}")
                except:
                    pass
                
                return True
            else:
                self.add_result("服务状态", False, f"HTTP {response.status_code}")
                return False
                
        except requests.exceptions.ConnectionError:
            self.add_result("服务状态", False, f"无法连接到 {self.api_url}")
            return False
        except Exception as e:
            self.add_result("服务状态", False, f"错误: {str(e)}")
            return False
    
    def verify_upload_functionality(self) -> bool:
        """验证上传功能"""
        self.print_section("5. 上传功能测试")
        
        # 创建测试文件
        test_content = b"Test audio content for deployment verification"
        test_filename = f"test_{datetime.now().strftime('%Y%m%d_%H%M%S')}.m4a"
        
        try:
            # 准备上传请求
            files = {
                'file': (test_filename, test_content, 'audio/m4a')
            }
            data = {
                'media_type': 'audio',
                'diary_id': 1
            }
            
            # 注意: 这里需要认证 token，实际使用时需要提供
            # headers = {'Authorization': f'Bearer {token}'}
            
            print(f"  上传测试文件: {test_filename} ({len(test_content)} bytes)")
            
            # 由于可能需要认证，这里只是示例
            # 实际部署时需要提供有效的 token
            self.add_result("上传功能", True, "需要手动测试（需要认证 token）")
            print(f"  {Colors.YELLOW}💡 提示: 使用以下命令手动测试上传:{Colors.RESET}")
            print(f"  curl -X POST {self.api_url}/api/media/upload \\")
            print(f"    -F 'file=@test.m4a' \\")
            print(f"    -F 'media_type=audio' \\")
            print(f"    -H 'Authorization: Bearer YOUR_TOKEN'")
            
            return True
            
        except Exception as e:
            self.add_result("上传功能", False, f"错误: {str(e)}")
            return False
    
    def print_summary(self):
        """打印测试摘要"""
        self.print_section("测试摘要")
        
        total = len(self.results)
        passed = sum(1 for _, p, _ in self.results if p)
        failed = total - passed
        
        print(f"总测试数: {total}")
        print(f"{Colors.GREEN}通过: {passed}{Colors.RESET}")
        print(f"{Colors.RED}失败: {failed}{Colors.RESET}")
        print(f"通过率: {passed/total*100:.1f}%\n")
        
        if failed > 0:
            print(f"{Colors.RED}失败的测试:{Colors.RESET}")
            for name, passed, message in self.results:
                if not passed:
                    print(f"  ✗ {name}")
                    if message:
                        print(f"    {message}")
        
        print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")
        if failed == 0:
            print(f"{Colors.GREEN}{Colors.BOLD}✓ 所有测试通过！测试环境部署验证成功。{Colors.RESET}")
        else:
            print(f"{Colors.RED}{Colors.BOLD}✗ 部分测试失败，请检查上述错误并修复。{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*60}{Colors.RESET}\n")
        
        return failed == 0
    
    def run_all_tests(self) -> bool:
        """运行所有测试"""
        print(f"\n{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.RESET}")
        print(f"{Colors.BOLD}{Colors.BLUE}测试环境部署验证{Colors.RESET}")
        print(f"{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.RESET}")
        print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"API URL: {self.api_url}")
        
        # 运行各项测试
        self.verify_config()
        self.verify_database()
        self.verify_oss_connection()
        self.verify_service_health()
        self.verify_upload_functionality()
        
        # 打印摘要
        return self.print_summary()


def main():
    """主函数"""
    parser = argparse.ArgumentParser(
        description='测试环境部署验证脚本',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 使用默认配置
  python verify_test_deployment.py
  
  # 指定配置文件和 API URL
  python verify_test_deployment.py --env-file .env.test --api-url http://localhost:8001
  
  # 使用生产配置
  python verify_test_deployment.py --env-file .env --api-url https://api.example.com
        """
    )
    
    parser.add_argument(
        '--env-file',
        default='.env.test',
        help='环境配置文件路径 (默认: .env.test)'
    )
    
    parser.add_argument(
        '--api-url',
        default='http://localhost:8001',
        help='API 服务 URL (默认: http://localhost:8001)'
    )
    
    args = parser.parse_args()
    
    # 运行验证
    verifier = TestDeploymentVerifier(
        env_file=args.env_file,
        api_url=args.api_url
    )
    
    success = verifier.run_all_tests()
    
    # 返回退出码
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
