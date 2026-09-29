"""
性能测试脚本

测试媒体文件上传的性能指标
需求: 7.1
"""
import asyncio
import sys
import os
import time
from pathlib import Path
import io
from typing import List, Dict
import statistics

# 添加项目根目录到 Python 路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from app.config import settings
from app.services.media_service import MediaService
from app.utils.media_utils import infer_media_type
import logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class PerformanceMetrics:
    """性能指标收集器"""
    
    def __init__(self):
        self.upload_times = []
        self.file_sizes = []
        self.success_count = 0
        self.failure_count = 0
        self.errors = []
    
    def record_upload(self, duration: float, file_size: int, success: bool, error: str = None):
        """记录上传结果"""
        self.upload_times.append(duration)
        self.file_sizes.append(file_size)
        
        if success:
            self.success_count += 1
        else:
            self.failure_count += 1
            if error:
                self.errors.append(error)
    
    def get_summary(self) -> Dict:
        """获取性能摘要"""
        if not self.upload_times:
            return {
                'total_uploads': 0,
                'success_count': 0,
                'failure_count': 0,
                'success_rate': 0.0
            }
        
        return {
            'total_uploads': len(self.upload_times),
            'success_count': self.success_count,
            'failure_count': self.failure_count,
            'success_rate': (self.success_count / len(self.upload_times)) * 100,
            'avg_upload_time': statistics.mean(self.upload_times),
            'min_upload_time': min(self.upload_times),
            'max_upload_time': max(self.upload_times),
            'median_upload_time': statistics.median(self.upload_times),
            'total_data_uploaded': sum(self.file_sizes),
            'avg_throughput_mbps': (sum(self.file_sizes) / sum(self.upload_times)) / (1024 * 1024) if sum(self.upload_times) > 0 else 0
        }
    
    def print_summary(self):
        """打印性能摘要"""
        summary = self.get_summary()
        
        logger.info("\n" + "=" * 60)
        logger.info("性能测试摘要")
        logger.info("=" * 60)
        logger.info(f"总上传次数: {summary['total_uploads']}")
        logger.info(f"成功次数: {summary['success_count']}")
        logger.info(f"失败次数: {summary['failure_count']}")
        logger.info(f"成功率: {summary['success_rate']:.2f}%")
        
        if summary['total_uploads'] > 0:
            logger.info(f"\n上传时间统计:")
            logger.info(f"  平均: {summary['avg_upload_time']:.3f} 秒")
            logger.info(f"  最小: {summary['min_upload_time']:.3f} 秒")
            logger.info(f"  最大: {summary['max_upload_time']:.3f} 秒")
            logger.info(f"  中位数: {summary['median_upload_time']:.3f} 秒")
            
            logger.info(f"\n数据传输统计:")
            logger.info(f"  总数据量: {summary['total_data_uploaded'] / (1024 * 1024):.2f} MB")
            logger.info(f"  平均吞吐量: {summary['avg_throughput_mbps']:.2f} MB/s")
        
        if self.errors:
            logger.info(f"\n错误列表:")
            for i, error in enumerate(self.errors[:5], 1):
                logger.info(f"  {i}. {error}")
            if len(self.errors) > 5:
                logger.info(f"  ... 还有 {len(self.errors) - 5} 个错误")
        
        logger.info("=" * 60)


def create_test_file(size_mb: int) -> tuple[bytes, str, str]:
    """
    创建指定大小的测试文件
    
    Args:
        size_mb: 文件大小（MB）
    
    Returns:
        (file_bytes, filename, content_type)
    """
    file_bytes = b"X" * (size_mb * 1024 * 1024)
    filename = f"test_{size_mb}mb.bin"
    content_type = "application/octet-stream"
    
    return file_bytes, filename, content_type


async def test_small_file_performance(iterations: int = 10):
    """
    测试小文件上传性能
    
    Args:
        iterations: 测试迭代次数
    """
    logger.info("\n" + "=" * 60)
    logger.info(f"测试 1: 小文件上传性能（1MB x {iterations} 次）")
    logger.info("=" * 60)
    
    metrics = PerformanceMetrics()
    media_service = MediaService()
    
    for i in range(iterations):
        try:
            # 创建 1MB 测试文件
            file_bytes, filename, content_type = create_test_file(1)
            
            # 推断媒体类型
            file_extension = Path(filename).suffix
            media_type = infer_media_type(content_type, file_extension) or "image"
            
            # 记录开始时间
            start_time = time.time()
            
            # 上传文件
            result = await media_service.upload_media_bytes(
                file_bytes=file_bytes,
                media_type=media_type,
                content_type=content_type,
                filename=filename
            )
            
            # 记录结束时间
            duration = time.time() - start_time
            
            # 记录指标
            metrics.record_upload(duration, len(file_bytes), True)
            
            logger.info(f"  [{i+1}/{iterations}] 上传成功: {duration:.3f} 秒, {result['storage_mode']} 模式")
            
        except Exception as e:
            duration = time.time() - start_time
            metrics.record_upload(duration, len(file_bytes), False, str(e))
            logger.error(f"  [{i+1}/{iterations}] 上传失败: {e}")
    
    # 打印摘要
    metrics.print_summary()
    
    return metrics


async def test_large_file_performance(iterations: int = 3):
    """
    测试大文件上传性能（200MB）
    
    Args:
        iterations: 测试迭代次数
    """
    logger.info("\n" + "=" * 60)
    logger.info(f"测试 2: 大文件上传性能（200MB x {iterations} 次）")
    logger.info("=" * 60)
    
    metrics = PerformanceMetrics()
    media_service = MediaService()
    
    for i in range(iterations):
        try:
            # 创建 200MB 测试文件
            logger.info(f"  [{i+1}/{iterations}] 创建 200MB 测试文件...")
            file_bytes, filename, content_type = create_test_file(200)
            
            # 推断媒体类型
            file_extension = Path(filename).suffix
            media_type = infer_media_type(content_type, file_extension) or "video"
            
            # 创建文件对象（模拟 UploadFile）
            file_object = io.BytesIO(file_bytes)
            
            class MockUploadFile:
                def __init__(self, file_obj, filename, content_type, size):
                    self.file = file_obj
                    self.filename = filename
                    self.content_type = content_type
                    self.size = size
                
                async def read(self, size=-1):
                    return self.file.read(size)
                
                async def seek(self, offset):
                    return self.file.seek(offset)
            
            mock_file = MockUploadFile(file_object, filename, content_type, len(file_bytes))
            
            # 记录开始时间
            logger.info(f"  [{i+1}/{iterations}] 开始上传...")
            start_time = time.time()
            
            # 使用流式上传
            result = await media_service.upload_media_streaming(
                file=mock_file,
                media_type=media_type,
                content_type=content_type,
                filename=filename
            )
            
            # 记录结束时间
            duration = time.time() - start_time
            
            # 记录指标
            metrics.record_upload(duration, len(file_bytes), True)
            
            logger.info(f"  [{i+1}/{iterations}] 上传成功: {duration:.3f} 秒 ({len(file_bytes) / (1024 * 1024) / duration:.2f} MB/s), {result['storage_mode']} 模式")
            
        except Exception as e:
            duration = time.time() - start_time
            metrics.record_upload(duration, len(file_bytes), False, str(e))
            logger.error(f"  [{i+1}/{iterations}] 上传失败: {e}")
    
    # 打印摘要
    metrics.print_summary()
    
    return metrics


async def test_concurrent_upload_performance(concurrent_count: int = 5, file_size_mb: int = 10):
    """
    测试并发上传性能
    
    Args:
        concurrent_count: 并发上传数量
        file_size_mb: 每个文件大小（MB）
    """
    logger.info("\n" + "=" * 60)
    logger.info(f"测试 3: 并发上传性能（{concurrent_count} 个 {file_size_mb}MB 文件同时上传）")
    logger.info("=" * 60)
    
    metrics = PerformanceMetrics()
    media_service = MediaService()
    
    async def upload_single_file(index: int):
        """上传单个文件"""
        try:
            # 创建测试文件
            file_bytes, filename, content_type = create_test_file(file_size_mb)
            filename = f"concurrent_test_{index}_{filename}"
            
            # 推断媒体类型
            file_extension = Path(filename).suffix
            media_type = infer_media_type(content_type, file_extension) or "image"
            
            # 记录开始时间
            start_time = time.time()
            
            # 上传文件
            result = await media_service.upload_media_bytes(
                file_bytes=file_bytes,
                media_type=media_type,
                content_type=content_type,
                filename=filename
            )
            
            # 记录结束时间
            duration = time.time() - start_time
            
            # 记录指标
            metrics.record_upload(duration, len(file_bytes), True)
            
            logger.info(f"  [文件 {index+1}] 上传成功: {duration:.3f} 秒")
            
        except Exception as e:
            duration = time.time() - start_time
            metrics.record_upload(duration, len(file_bytes), False, str(e))
            logger.error(f"  [文件 {index+1}] 上传失败: {e}")
    
    # 记录总开始时间
    total_start_time = time.time()
    
    # 并发上传
    tasks = [upload_single_file(i) for i in range(concurrent_count)]
    await asyncio.gather(*tasks)
    
    # 记录总结束时间
    total_duration = time.time() - total_start_time
    
    logger.info(f"\n总耗时: {total_duration:.3f} 秒")
    logger.info(f"平均每个文件: {total_duration / concurrent_count:.3f} 秒")
    
    # 打印摘要
    metrics.print_summary()
    
    return metrics


async def run_all_performance_tests():
    """运行所有性能测试"""
    logger.info("=" * 60)
    logger.info("开始性能测试")
    logger.info("=" * 60)
    logger.info(f"OSS 模式: {'启用' if settings.oss_enabled else '禁用'}")
    
    if settings.oss_enabled:
        logger.info(f"OSS 端点: {settings.oss_endpoint}")
        logger.info(f"OSS 存储桶: {settings.oss_bucket}")
    else:
        logger.info(f"本地上传目录: {settings.upload_dir}")
    
    # 测试 1: 小文件上传性能
    small_file_metrics = await test_small_file_performance(iterations=10)
    
    # 测试 2: 大文件上传性能
    large_file_metrics = await test_large_file_performance(iterations=3)
    
    # 测试 3: 并发上传性能
    concurrent_metrics = await test_concurrent_upload_performance(concurrent_count=5, file_size_mb=10)
    
    # 总结
    logger.info("\n" + "=" * 60)
    logger.info("所有性能测试完成")
    logger.info("=" * 60)
    
    # 评估性能
    small_summary = small_file_metrics.get_summary()
    large_summary = large_file_metrics.get_summary()
    concurrent_summary = concurrent_metrics.get_summary()
    
    logger.info("\n性能评估:")
    
    # 小文件性能评估
    if small_summary['success_rate'] >= 95 and small_summary['avg_upload_time'] < 2.0:
        logger.info("  ✓ 小文件上传性能: 优秀")
    elif small_summary['success_rate'] >= 90 and small_summary['avg_upload_time'] < 5.0:
        logger.info("  ⚠ 小文件上传性能: 良好")
    else:
        logger.info("  ✗ 小文件上传性能: 需要优化")
    
    # 大文件性能评估
    if large_summary['success_rate'] >= 95 and large_summary['avg_upload_time'] < 30.0:
        logger.info("  ✓ 大文件上传性能: 优秀")
    elif large_summary['success_rate'] >= 90 and large_summary['avg_upload_time'] < 60.0:
        logger.info("  ⚠ 大文件上传性能: 良好")
    else:
        logger.info("  ✗ 大文件上传性能: 需要优化")
    
    # 并发性能评估
    if concurrent_summary['success_rate'] >= 95:
        logger.info("  ✓ 并发上传性能: 优秀")
    elif concurrent_summary['success_rate'] >= 90:
        logger.info("  ⚠ 并发上传性能: 良好")
    else:
        logger.info("  ✗ 并发上传性能: 需要优化")
    
    # 总体评估
    overall_success = (
        small_summary['success_rate'] >= 90 and
        large_summary['success_rate'] >= 90 and
        concurrent_summary['success_rate'] >= 90
    )
    
    if overall_success:
        logger.info("\n✓ 性能测试通过！系统性能符合要求。")
        return True
    else:
        logger.error("\n✗ 性能测试未通过，请检查系统配置和网络状况。")
        return False


if __name__ == "__main__":
    success = asyncio.run(run_all_performance_tests())
    sys.exit(0 if success else 1)
