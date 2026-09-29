"""
手动上传测试脚本

用于手动测试媒体文件上传功能
需求: 10.1, 10.2, 10.3, 10.4
"""
import asyncio
import sys
import os
from pathlib import Path
import io

# 添加项目根目录到 Python 路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from app.config import settings
from app.services.media_service import MediaService
from app.services.oss_service import OSSService
from app.utils.media_utils import infer_media_type
import logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def create_test_file(file_type: str, size_kb: int = 10) -> tuple[bytes, str, str]:
    """
    创建测试文件
    
    Args:
        file_type: 文件类型 (image/audio/video)
        size_kb: 文件大小（KB）
    
    Returns:
        (file_bytes, filename, content_type)
    """
    # 生成测试数据
    file_bytes = b"TEST_FILE_CONTENT_" + b"X" * (size_kb * 1024 - 18)
    
    # 根据类型设置文件名和 content_type
    if file_type == "image":
        filename = "test_image.jpg"
        content_type = "image/jpeg"
    elif file_type == "audio":
        filename = "test_audio.m4a"
        content_type = "audio/m4a"
    elif file_type == "video":
        filename = "test_video.mp4"
        content_type = "video/mp4"
    else:
        filename = "test_file.bin"
        content_type = "application/octet-stream"
    
    return file_bytes, filename, content_type


async def test_small_file_upload():
    """测试小文件上传（< 50MB）"""
    logger.info("\n" + "=" * 60)
    logger.info("测试 1: 小文件上传（10KB 音频文件）")
    logger.info("=" * 60)
    
    try:
        # 创建测试文件
        file_bytes, filename, content_type = create_test_file("audio", size_kb=10)
        logger.info(f"创建测试文件: {filename}, 大小: {len(file_bytes)} 字节")
        
        # 推断媒体类型
        file_extension = Path(filename).suffix
        media_type = infer_media_type(content_type, file_extension)
        logger.info(f"推断媒体类型: {media_type}")
        
        # 初始化服务
        media_service = MediaService()
        
        # 上传文件
        logger.info("开始上传...")
        result = await media_service.upload_media_bytes(
            file_bytes=file_bytes,
            media_type=media_type,
            content_type=content_type,
            filename=filename
        )
        
        # 验证结果
        logger.info("✓ 上传成功！")
        logger.info(f"  存储模式: {result['storage_mode']}")
        logger.info(f"  URL: {result['url']}")
        logger.info(f"  文件大小: {result['size_bytes']} 字节")
        
        if result['storage_mode'] == 'oss':
            logger.info(f"  OSS Bucket: {result.get('bucket')}")
            logger.info(f"  OSS Key: {result.get('key')}")
        else:
            logger.info(f"  本地路径: {result.get('file_path')}")
        
        return True
        
    except Exception as e:
        logger.error(f"✗ 上传失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def test_large_file_upload():
    """测试大文件上传（> 50MB，使用流式上传）"""
    logger.info("\n" + "=" * 60)
    logger.info("测试 2: 大文件上传（60MB 视频文件，流式上传）")
    logger.info("=" * 60)
    
    try:
        # 创建测试文件（60MB）
        file_bytes, filename, content_type = create_test_file("video", size_kb=60 * 1024)
        logger.info(f"创建测试文件: {filename}, 大小: {len(file_bytes) / 1024 / 1024:.2f} MB")
        
        # 推断媒体类型
        file_extension = Path(filename).suffix
        media_type = infer_media_type(content_type, file_extension)
        logger.info(f"推断媒体类型: {media_type}")
        
        # 创建文件对象（模拟 UploadFile）
        file_object = io.BytesIO(file_bytes)
        
        # 初始化服务
        media_service = MediaService()
        
        # 使用流式上传
        logger.info("开始流式上传...")
        
        # 创建模拟的 UploadFile 对象
        class MockUploadFile:
            def __init__(self, file_obj, filename, content_type):
                self.file = file_obj
                self.filename = filename
                self.content_type = content_type
                self.size = len(file_bytes)
            
            async def read(self, size=-1):
                return self.file.read(size)
            
            async def seek(self, offset):
                return self.file.seek(offset)
        
        mock_file = MockUploadFile(file_object, filename, content_type)
        
        result = await media_service.upload_media_streaming(
            file=mock_file,
            media_type=media_type,
            content_type=content_type,
            filename=filename
        )
        
        # 验证结果
        logger.info("✓ 上传成功！")
        logger.info(f"  存储模式: {result['storage_mode']}")
        logger.info(f"  URL: {result['url']}")
        logger.info(f"  文件大小: {result['size_bytes'] / 1024 / 1024:.2f} MB")
        
        if result['storage_mode'] == 'oss':
            logger.info(f"  OSS Bucket: {result.get('bucket')}")
            logger.info(f"  OSS Key: {result.get('key')}")
        
        return True
        
    except Exception as e:
        logger.error(f"✗ 上传失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def test_multiple_file_types():
    """测试多种文件类型上传"""
    logger.info("\n" + "=" * 60)
    logger.info("测试 3: 多种文件类型上传")
    logger.info("=" * 60)
    
    test_cases = [
        ("image", "test.jpg", "image/jpeg"),
        ("image", "test.png", "image/png"),
        ("audio", "test.m4a", "audio/m4a"),
        ("audio", "test.mp3", "audio/mpeg"),
        ("video", "test.mp4", "video/mp4"),
    ]
    
    results = []
    
    for media_type, filename, content_type in test_cases:
        logger.info(f"\n测试文件: {filename} ({content_type})")
        
        try:
            # 创建测试文件
            file_bytes = b"TEST_CONTENT_" + filename.encode()
            
            # 推断媒体类型
            file_extension = Path(filename).suffix
            inferred_type = infer_media_type(content_type, file_extension)
            
            if inferred_type != media_type:
                logger.warning(f"  类型推断不匹配: 期望 {media_type}, 得到 {inferred_type}")
            
            # 上传文件
            media_service = MediaService()
            result = await media_service.upload_media_bytes(
                file_bytes=file_bytes,
                media_type=inferred_type,
                content_type=content_type,
                filename=filename
            )
            
            logger.info(f"  ✓ 上传成功: {result['url']}")
            results.append((filename, True))
            
        except Exception as e:
            logger.error(f"  ✗ 上传失败: {e}")
            results.append((filename, False))
    
    # 总结
    success_count = sum(1 for _, success in results if success)
    logger.info(f"\n总结: {success_count}/{len(results)} 个文件上传成功")
    
    return success_count == len(results)


async def test_oss_key_format():
    """测试 OSS 键格式"""
    logger.info("\n" + "=" * 60)
    logger.info("测试 4: OSS 键格式验证")
    logger.info("=" * 60)
    
    if not settings.oss_enabled:
        logger.info("OSS 未启用，跳过此测试")
        return True
    
    try:
        # 初始化 OSS 服务
        oss_service = OSSService(
            endpoint=settings.oss_endpoint,
            bucket_name=settings.oss_bucket,
            access_key_id=settings.oss_access_key_id,
            access_key_secret=settings.oss_access_key_secret,
            public_base_url=settings.oss_public_base_url,
            prefix=settings.oss_prefix
        )
        
        # 生成多个键并验证格式
        extensions = ['.jpg', '.m4a', '.mp4', '.png']
        
        for ext in extensions:
            key = oss_service._generate_key(ext)
            logger.info(f"生成的键: {key}")
            
            # 验证格式: media/YYYY/MM/DD/{uuid}.{ext}
            parts = key.split('/')
            
            if len(parts) != 5:
                logger.error(f"  ✗ 键格式错误: 期望 5 个部分，得到 {len(parts)}")
                return False
            
            if not parts[0] == settings.oss_prefix.rstrip('/'):
                logger.error(f"  ✗ 前缀错误: 期望 {settings.oss_prefix.rstrip('/')}, 得到 {parts[0]}")
                return False
            
            if not key.endswith(ext):
                logger.error(f"  ✗ 扩展名错误: 期望 {ext}, 得到 {key[-len(ext):]}")
                return False
            
            logger.info(f"  ✓ 格式正确")
        
        logger.info("\n✓ 所有键格式验证通过")
        return True
        
    except Exception as e:
        logger.error(f"✗ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def run_all_tests():
    """运行所有手动测试"""
    logger.info("=" * 60)
    logger.info("开始手动上传测试")
    logger.info("=" * 60)
    logger.info(f"OSS 模式: {'启用' if settings.oss_enabled else '禁用'}")
    
    if settings.oss_enabled:
        logger.info(f"OSS 端点: {settings.oss_endpoint}")
        logger.info(f"OSS 存储桶: {settings.oss_bucket}")
    else:
        logger.info(f"本地上传目录: {settings.upload_dir}")
    
    # 运行测试
    results = []
    
    # 测试 1: 小文件上传
    results.append(("小文件上传", await test_small_file_upload()))
    
    # 测试 2: 大文件上传
    results.append(("大文件上传", await test_large_file_upload()))
    
    # 测试 3: 多种文件类型
    results.append(("多种文件类型", await test_multiple_file_types()))
    
    # 测试 4: OSS 键格式
    results.append(("OSS 键格式", await test_oss_key_format()))
    
    # 打印总结
    logger.info("\n" + "=" * 60)
    logger.info("测试总结")
    logger.info("=" * 60)
    
    for test_name, success in results:
        status = "✓ 通过" if success else "✗ 失败"
        logger.info(f"{test_name}: {status}")
    
    success_count = sum(1 for _, success in results if success)
    logger.info(f"\n总计: {success_count}/{len(results)} 个测试通过")
    
    if success_count == len(results):
        logger.info("\n✓ 所有测试通过！")
        return True
    else:
        logger.error("\n✗ 部分测试失败，请检查日志。")
        return False


if __name__ == "__main__":
    success = asyncio.run(run_all_tests())
    sys.exit(0 if success else 1)
