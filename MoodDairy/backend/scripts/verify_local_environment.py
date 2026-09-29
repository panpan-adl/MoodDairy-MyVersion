"""
本地环境验证脚本

验证 OSS 迁移功能的本地环境配置和功能
需求: 10.1, 10.2, 10.3, 10.4, 10.5
"""
import asyncio
import sys
import os
from pathlib import Path

# 添加项目根目录到 Python 路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from app.config import settings
from app.database.connection import get_db
from app.models.database import MediaFile
from sqlalchemy import text
import logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class EnvironmentVerifier:
    """环境验证器"""
    
    def __init__(self):
        self.passed_checks = 0
        self.failed_checks = 0
        self.warnings = 0
    
    def check(self, name: str, condition: bool, error_msg: str = "", warning: bool = False):
        """执行检查"""
        if condition:
            logger.info(f"✓ {name}")
            self.passed_checks += 1
            return True
        else:
            if warning:
                logger.warning(f"⚠ {name}: {error_msg}")
                self.warnings += 1
            else:
                logger.error(f"✗ {name}: {error_msg}")
                self.failed_checks += 1
            return False
    
    def print_summary(self):
        """打印总结"""
        logger.info("\n" + "=" * 60)
        logger.info("验证总结")
        logger.info("=" * 60)
        logger.info(f"通过: {self.passed_checks}")
        logger.info(f"失败: {self.failed_checks}")
        logger.info(f"警告: {self.warnings}")
        logger.info("=" * 60)
        
        if self.failed_checks == 0:
            logger.info("✓ 所有关键检查通过！环境配置正确。")
            return True
        else:
            logger.error("✗ 存在失败的检查，请修复后重试。")
            return False


async def verify_environment():
    """验证本地环境"""
    verifier = EnvironmentVerifier()
    
    logger.info("=" * 60)
    logger.info("开始本地环境验证")
    logger.info("=" * 60)
    
    # 1. 配置验证
    logger.info("\n[1] 配置验证")
    logger.info("-" * 60)
    
    # 检查 OSS 配置
    verifier.check(
        "OSS 配置加载",
        settings.oss_enabled is not None,
        "无法读取 OSS_ENABLED 配置"
    )
    
    if settings.oss_enabled:
        logger.info(f"  OSS 模式: 启用")
        verifier.check(
            "OSS 端点配置",
            settings.oss_endpoint is not None and len(settings.oss_endpoint) > 0,
            "OSS_ENDPOINT 未配置"
        )
        verifier.check(
            "OSS 存储桶配置",
            settings.oss_bucket is not None and len(settings.oss_bucket) > 0,
            "OSS_BUCKET 未配置"
        )
        verifier.check(
            "OSS 访问密钥配置",
            settings.oss_access_key_id is not None and len(settings.oss_access_key_id) > 0,
            "OSS_ACCESS_KEY_ID 未配置"
        )
        verifier.check(
            "OSS 密钥 Secret 配置",
            settings.oss_access_key_secret is not None and len(settings.oss_access_key_secret) > 0,
            "OSS_ACCESS_KEY_SECRET 未配置"
        )
        verifier.check(
            "OSS 公共 URL 配置",
            settings.oss_public_base_url is not None and len(settings.oss_public_base_url) > 0,
            "OSS_PUBLIC_BASE_URL 未配置"
        )
    else:
        logger.info(f"  OSS 模式: 禁用（使用本地存储）")
        verifier.check(
            "本地上传目录配置",
            settings.upload_dir is not None,
            "UPLOAD_DIR 未配置"
        )
    
    # 2. 依赖验证
    logger.info("\n[2] 依赖验证")
    logger.info("-" * 60)
    
    # 检查 oss2 包
    try:
        import oss2
        verifier.check("oss2 包已安装", True)
        logger.info(f"  oss2 版本: {oss2.__version__}")
    except ImportError:
        verifier.check("oss2 包已安装", False, "请运行: pip install oss2>=2.18.0")
    
    # 检查其他关键依赖
    try:
        import sqlalchemy
        verifier.check("sqlalchemy 包已安装", True)
    except ImportError:
        verifier.check("sqlalchemy 包已安装", False, "请运行: pip install -r requirements.txt")
    
    try:
        import fastapi
        verifier.check("fastapi 包已安装", True)
    except ImportError:
        verifier.check("fastapi 包已安装", False, "请运行: pip install -r requirements.txt")
    
    # 3. 数据库验证
    logger.info("\n[3] 数据库验证")
    logger.info("-" * 60)
    
    try:
        # 获取数据库连接
        async for db in get_db():
            # 检查数据库连接
            try:
                await db.execute(text("SELECT 1"))
                verifier.check("数据库连接", True)
            except Exception as e:
                verifier.check("数据库连接", False, f"无法连接到数据库: {e}")
                break
            
            # 检查 media_files 表是否存在
            try:
                result = await db.execute(text("""
                    SELECT EXISTS (
                        SELECT FROM information_schema.tables 
                        WHERE table_name = 'media_files'
                    )
                """))
                table_exists = result.scalar()
                verifier.check(
                    "media_files 表存在",
                    table_exists,
                    "请运行数据库迁移: python run_migrations.py"
                )
                
                if table_exists:
                    # 检查表结构
                    result = await db.execute(text("""
                        SELECT column_name 
                        FROM information_schema.columns 
                        WHERE table_name = 'media_files'
                        ORDER BY ordinal_position
                    """))
                    columns = [row[0] for row in result.fetchall()]
                    
                    required_columns = [
                        'id', 'diary_id', 'type', 'url', 'oss_bucket', 
                        'oss_key', 'content_type', 'size_bytes', 
                        'duration_ms', 'created_at'
                    ]
                    
                    missing_columns = [col for col in required_columns if col not in columns]
                    verifier.check(
                        "media_files 表结构完整",
                        len(missing_columns) == 0,
                        f"缺少列: {', '.join(missing_columns)}"
                    )
                    
                    if len(missing_columns) == 0:
                        logger.info(f"  表列: {', '.join(columns)}")
                    
                    # 检查索引
                    result = await db.execute(text("""
                        SELECT indexname 
                        FROM pg_indexes 
                        WHERE tablename = 'media_files'
                    """))
                    indexes = [row[0] for row in result.fetchall()]
                    
                    verifier.check(
                        "diary_id 索引存在",
                        any('diary_id' in idx for idx in indexes),
                        "缺少 diary_id 索引",
                        warning=True
                    )
                    verifier.check(
                        "type 索引存在",
                        any('type' in idx for idx in indexes),
                        "缺少 type 索引",
                        warning=True
                    )
                    
            except Exception as e:
                verifier.check("media_files 表检查", False, f"检查失败: {e}")
            
            break
    except Exception as e:
        verifier.check("数据库验证", False, f"数据库验证失败: {e}")
    
    # 4. OSS 连接验证（如果启用）
    if settings.oss_enabled:
        logger.info("\n[4] OSS 连接验证")
        logger.info("-" * 60)
        
        try:
            import oss2
            
            # 初始化 OSS 连接
            auth = oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret)
            bucket = oss2.Bucket(auth, settings.oss_endpoint, settings.oss_bucket)
            
            # 测试连接
            try:
                bucket_info = bucket.get_bucket_info()
                verifier.check("OSS 连接成功", True)
                logger.info(f"  存储桶名称: {bucket_info.name}")
                logger.info(f"  存储桶位置: {bucket_info.location}")
                logger.info(f"  创建时间: {bucket_info.creation_date}")
                
                # 检查 ACL 权限
                try:
                    acl = bucket.get_bucket_acl()
                    logger.info(f"  ACL 权限: {acl.acl}")
                    verifier.check(
                        "OSS 存储桶 ACL 为 public-read",
                        acl.acl == 'public-read',
                        f"当前 ACL: {acl.acl}，建议设置为 public-read",
                        warning=True
                    )
                except Exception as e:
                    verifier.check("OSS ACL 检查", False, f"无法获取 ACL: {e}", warning=True)
                
            except oss2.exceptions.NoSuchBucket:
                verifier.check("OSS 连接成功", False, f"存储桶不存在: {settings.oss_bucket}")
            except oss2.exceptions.AccessDenied:
                verifier.check("OSS 连接成功", False, "访问被拒绝，请检查 AccessKey 权限")
            except Exception as e:
                verifier.check("OSS 连接成功", False, f"连接失败: {e}")
                
        except ImportError:
            verifier.check("OSS 连接验证", False, "oss2 包未安装")
        except Exception as e:
            verifier.check("OSS 连接验证", False, f"验证失败: {e}")
    
    # 5. 本地存储验证（如果未启用 OSS）
    if not settings.oss_enabled:
        logger.info("\n[4] 本地存储验证")
        logger.info("-" * 60)
        
        upload_dir = Path(settings.upload_dir)
        verifier.check(
            "上传目录存在",
            upload_dir.exists(),
            f"目录不存在: {upload_dir}"
        )
        
        if upload_dir.exists():
            verifier.check(
                "上传目录可写",
                os.access(upload_dir, os.W_OK),
                f"目录不可写: {upload_dir}"
            )
    
    # 6. 服务模块验证
    logger.info("\n[5] 服务模块验证")
    logger.info("-" * 60)
    
    try:
        from app.services.oss_service import OSSService
        verifier.check("OSSService 模块可导入", True)
    except ImportError as e:
        verifier.check("OSSService 模块可导入", False, f"导入失败: {e}")
    
    try:
        from app.services.media_service import MediaService
        verifier.check("MediaService 模块可导入", True)
    except ImportError as e:
        verifier.check("MediaService 模块可导入", False, f"导入失败: {e}")
    
    try:
        from app.utils.media_utils import infer_media_type
        verifier.check("media_utils 模块可导入", True)
    except ImportError as e:
        verifier.check("media_utils 模块可导入", False, f"导入失败: {e}")
    
    try:
        from app.routers.media import router
        verifier.check("media router 可导入", True)
    except ImportError as e:
        verifier.check("media router 可导入", False, f"导入失败: {e}")
    
    # 打印总结
    success = verifier.print_summary()
    
    if success:
        logger.info("\n✓ 本地环境验证通过！可以继续进行测试。")
        logger.info("\n下一步:")
        logger.info("  1. 运行单元测试: pytest tests/")
        logger.info("  2. 运行集成测试: pytest tests/test_integration.py")
        logger.info("  3. 手动测试上传功能")
    else:
        logger.error("\n✗ 本地环境验证失败，请修复上述问题后重试。")
    
    return success


if __name__ == "__main__":
    success = asyncio.run(verify_environment())
    sys.exit(0 if success else 1)
