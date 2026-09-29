"""
媒体服务层

提供媒体文件处理相关的业务逻辑，包括：
- 文件保存和组织
- 图片压缩和缩略图生成
- 文件URL生成
- OSS 云存储集成
"""
import os
import uuid
import logging
from datetime import datetime
from pathlib import Path
from typing import Optional, Tuple, Dict
from fastapi import UploadFile
from PIL import Image
import io

from app.services.oss_service import OSSService
from app.config import settings

logger = logging.getLogger(__name__)


class MediaService:
    """媒体服务类"""
    
    # 图片压缩配置
    MAX_IMAGE_SIZE = (1920, 1920)  # 最大尺寸
    THUMBNAIL_SIZE = (300, 300)  # 缩略图尺寸
    IMAGE_QUALITY = 85  # JPEG质量
    THUMBNAIL_QUALITY = 75  # 缩略图质量
    
    def __init__(self, upload_dir: str = "./uploads"):
        """
        初始化媒体服务
        
        Args:
            upload_dir: 上传文件的根目录
        """
        self.upload_dir = Path(upload_dir)
        # 确保上传目录存在
        self.upload_dir.mkdir(parents=True, exist_ok=True)
        
        # 初始化 OSS 服务（如果启用）
        self.oss_enabled = settings.oss_enabled
        self.oss_fallback_enabled = settings.oss_fallback_to_local
        self.oss_service = None
        
        if self.oss_enabled:
            try:
                self.oss_service = OSSService(
                    endpoint=settings.oss_endpoint,
                    bucket_name=settings.oss_bucket,
                    access_key_id=settings.oss_access_key_id,
                    access_key_secret=settings.oss_access_key_secret,
                    public_base_url=settings.oss_public_base_url,
                    prefix=settings.oss_prefix
                )
                # 验证 OSS 连接性
                self.oss_service.bucket.get_bucket_info()
                logger.info("OSS 连接验证成功")
            except Exception as e:
                logger.error(f"OSS 初始化失败: {e}")
                if not self.oss_fallback_enabled:
                    raise Exception("OSS 不可用且未启用回退模式")
                logger.warning("OSS 不可用，回退到本地存储模式")
                self.oss_enabled = False
    
    async def save_file(
        self, 
        file: UploadFile, 
        media_type: str
    ) -> str:
        """
        保存上传的文件并返回文件路径
        
        文件按照日期和类型组织：
        uploads/YYYY/MM/DD/{media_type}/{uuid}_{filename}
        
        对于图片文件，会自动进行压缩优化
        
        Args:
            file: 上传的文件对象
            media_type: 媒体类型(text/image/audio/video)
            
        Returns:
            str: 相对于upload_dir的文件路径
            
        验证需求: 4.6, 5.7, 6.6
        """
        # 获取当前日期
        now = datetime.now()
        year = now.strftime("%Y")
        month = now.strftime("%m")
        day = now.strftime("%d")
        
        # 构建目录结构: uploads/YYYY/MM/DD/{media_type}/
        file_dir = self.upload_dir / year / month / day / media_type
        file_dir.mkdir(parents=True, exist_ok=True)
        
        # 生成唯一文件名: {uuid}_{original_filename}
        file_extension = Path(file.filename).suffix if file.filename else ""
        unique_filename = f"{uuid.uuid4()}{file_extension}"
        
        # 完整文件路径
        file_path = file_dir / unique_filename
        
        # 读取文件内容
        content = await file.read()
        
        # 如果是图片，进行压缩处理
        if media_type == "image" and self._is_image_file(file.filename):
            content = self._compress_image(content, file_extension)
        
        # 保存文件
        with open(file_path, "wb") as f:
            f.write(content)
        
        # 返回相对路径（相对于upload_dir）
        # 使用 as_posix() 确保路径使用正斜杠（URL 格式）
        relative_path = file_path.relative_to(self.upload_dir)
        return relative_path.as_posix()  # 转换为 POSIX 格式（使用 /）
    
    async def save_file_with_thumbnail(
        self,
        file: UploadFile,
        media_type: str
    ) -> Tuple[str, Optional[str]]:
        """
        保存文件并生成缩略图（仅用于图片和视频）
        
        Args:
            file: 上传的文件对象
            media_type: 媒体类型
            
        Returns:
            Tuple[str, Optional[str]]: (文件路径, 缩略图路径)
            
        性能优化: 任务31
        """
        # 保存原始文件（已压缩）
        file_path = await self.save_file(file, media_type)
        
        # 如果是图片，生成缩略图
        thumbnail_path = None
        if media_type == "image" and self._is_image_file(file.filename):
            thumbnail_path = await self._generate_thumbnail(file_path)
        
        return file_path, thumbnail_path
    
    def _is_image_file(self, filename: Optional[str]) -> bool:
        """
        检查文件是否为图片
        
        Args:
            filename: 文件名
            
        Returns:
            bool: 是否为图片文件
        """
        if not filename:
            return False
        
        image_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp'}
        extension = Path(filename).suffix.lower()
        return extension in image_extensions
    
    def _compress_image(self, image_data: bytes, file_extension: str) -> bytes:
        """
        压缩图片
        
        Args:
            image_data: 原始图片数据
            file_extension: 文件扩展名
            
        Returns:
            bytes: 压缩后的图片数据
            
        性能优化: 任务31
        """
        try:
            # 打开图片
            image = Image.open(io.BytesIO(image_data))
            
            # 转换RGBA为RGB（JPEG不支持透明度）
            if image.mode in ('RGBA', 'LA', 'P'):
                # 创建白色背景
                background = Image.new('RGB', image.size, (255, 255, 255))
                if image.mode == 'P':
                    image = image.convert('RGBA')
                background.paste(image, mask=image.split()[-1] if image.mode in ('RGBA', 'LA') else None)
                image = background
            elif image.mode != 'RGB':
                image = image.convert('RGB')
            
            # 调整大小（如果超过最大尺寸）
            if image.size[0] > self.MAX_IMAGE_SIZE[0] or image.size[1] > self.MAX_IMAGE_SIZE[1]:
                image.thumbnail(self.MAX_IMAGE_SIZE, Image.Resampling.LANCZOS)
            
            # 保存到字节流
            output = io.BytesIO()
            
            # 根据扩展名选择格式
            if file_extension.lower() in ['.jpg', '.jpeg']:
                image.save(output, format='JPEG', quality=self.IMAGE_QUALITY, optimize=True)
            elif file_extension.lower() == '.png':
                image.save(output, format='PNG', optimize=True)
            elif file_extension.lower() == '.webp':
                image.save(output, format='WEBP', quality=self.IMAGE_QUALITY)
            else:
                # 默认使用JPEG
                image.save(output, format='JPEG', quality=self.IMAGE_QUALITY, optimize=True)
            
            return output.getvalue()
            
        except Exception as e:
            # 如果压缩失败，返回原始数据
            print(f"图片压缩失败: {e}")
            return image_data
    
    async def _generate_thumbnail(self, file_path: str) -> str:
        """
        生成缩略图
        
        Args:
            file_path: 原始文件相对路径
            
        Returns:
            str: 缩略图相对路径
            
        性能优化: 任务31
        """
        try:
            # 构建完整路径
            full_path = self.upload_dir / file_path
            
            # 打开图片
            image = Image.open(full_path)
            
            # 转换为RGB
            if image.mode in ('RGBA', 'LA', 'P'):
                background = Image.new('RGB', image.size, (255, 255, 255))
                if image.mode == 'P':
                    image = image.convert('RGBA')
                background.paste(image, mask=image.split()[-1] if image.mode in ('RGBA', 'LA') else None)
                image = background
            elif image.mode != 'RGB':
                image = image.convert('RGB')
            
            # 生成缩略图
            image.thumbnail(self.THUMBNAIL_SIZE, Image.Resampling.LANCZOS)
            
            # 构建缩略图路径
            path_obj = Path(file_path)
            thumbnail_filename = f"{path_obj.stem}_thumb{path_obj.suffix}"
            thumbnail_path = path_obj.parent / thumbnail_filename
            thumbnail_full_path = self.upload_dir / thumbnail_path
            
            # 保存缩略图
            image.save(thumbnail_full_path, format='JPEG', quality=self.THUMBNAIL_QUALITY, optimize=True)
            
            return str(thumbnail_path)
            
        except Exception as e:
            print(f"缩略图生成失败: {e}")
            return None
    
    def get_file_url(self, file_path: str, base_url: str = "/media") -> str:
        """
        生成文件访问URL
        
        Args:
            file_path: 文件相对路径
            base_url: API基础URL
            
        Returns:
            str: 文件访问URL
            
        验证需求: 4.6, 5.7, 6.6
        """
        if file_path.startswith(("http://", "https://")):
            return file_path

        # 规范化路径分隔符（Windows使用反斜杠，需要转换为正斜杠）
        normalized_path = file_path.replace("\\", "/")
        return f"{base_url}/{normalized_path}"
    
    async def upload_media_bytes(
        self,
        file_bytes: bytes,
        media_type: str,
        content_type: str,
        filename: str
    ) -> Dict[str, any]:
        """
        上传媒体文件（内存模式，适用于小文件 <50MB）
        
        改进的错误处理：
        - OSS 失败时自动回退到本地（即使未启用回退）
        - 确保文件一定能保存成功
        - 避免因 OSS 问题导致整个请求失败
        
        Args:
            file_bytes: 文件字节内容
            media_type: 媒体类型（image/audio/video）
            content_type: MIME 类型
            filename: 原始文件名
        
        Returns:
            Dict: {
                'url': 访问 URL,
                'bucket': 存储桶名称（OSS 模式）,
                'key': 对象键（OSS 模式）,
                'file_path': 本地路径（本地模式）,
                'size_bytes': 文件大小,
                'storage_mode': 'oss' 或 'local'
            }
        """
        file_size = len(file_bytes)
        file_extension = Path(filename).suffix if filename else ""
        
        # 根据配置选择存储方式
        if self.oss_enabled and self.oss_service:
            # OSS 模式
            try:
                result = self.oss_service.upload_bytes(
                    file_bytes=file_bytes,
                    content_type=content_type,
                    file_extension=file_extension
                )
                result['storage_mode'] = 'oss'
                logger.info(f"OSS 上传成功: url={result['url']}")
                return result
            except Exception as e:
                # 捕获所有 OSS 错误（包括 oss2.exceptions.OssError）
                logger.error(f"OSS 上传失败: {e}")
                
                # 改进：即使未启用回退，也尝试本地存储作为最后手段
                # 这样可以避免因 OSS 问题导致整个上传失败
                if not self.oss_fallback_enabled:
                    logger.warning("OSS 回退未启用，但仍尝试本地存储作为最后手段")
                else:
                    logger.warning("OSS 上传失败，回退到本地存储")
        
        # 本地存储模式（回退或默认）
        logger.info("使用本地存储模式")
        try:
            file_path = self._save_bytes_to_local(file_bytes, media_type, filename)
            url = self.get_file_url(file_path)
            
            return {
                'url': url,
                'file_path': file_path,
                'size_bytes': file_size,
                'storage_mode': 'local'
            }
        except Exception as e:
            # 本地存储也失败，这是严重错误
            logger.error(f"本地存储失败: {e}")
            raise Exception(f"文件保存失败（OSS 和本地存储都失败）: {str(e)}")
    
    async def upload_media_streaming(
        self,
        file: UploadFile,
        media_type: str,
        content_type: str,
        filename: str
    ) -> Dict[str, any]:
        """
        上传媒体文件（流式模式，适用于大文件 >50MB）
        
        重要：此方法避免将整个文件读入内存，适用于 200MB 大文件场景
        
        改进的错误处理：
        - OSS 失败时自动回退到本地（即使未启用回退）
        - 确保文件一定能保存成功
        - 避免因 OSS 问题导致整个请求失败
        
        Args:
            file: FastAPI UploadFile 对象（未读取）
            media_type: 媒体类型
            content_type: MIME 类型
            filename: 原始文件名
        
        Returns:
            Dict: 同 upload_media_bytes
        """
        file_extension = Path(filename).suffix if filename else ""
        
        # 根据配置选择存储方式
        if self.oss_enabled and self.oss_service:
            # OSS 流式上传
            try:
                result = self.oss_service.upload_file_object(
                    file_object=file.file,  # SpooledTemporaryFile
                    content_type=content_type,
                    file_extension=file_extension
                )
                result['storage_mode'] = 'oss'
                logger.info(f"OSS 流式上传成功: url={result['url']}")
                return result
            except Exception as e:
                # 捕获所有 OSS 错误（包括 oss2.exceptions.OssError）
                logger.error(f"OSS 流式上传失败: {e}")
                
                # 改进：即使未启用回退，也尝试本地存储作为最后手段
                if not self.oss_fallback_enabled:
                    logger.warning("OSS 回退未启用，但仍尝试本地存储作为最后手段")
                else:
                    logger.warning("OSS 流式上传失败，回退到本地存储")
                
                # 重置文件指针用于本地保存
                try:
                    await file.seek(0)
                except Exception as seek_error:
                    logger.error(f"无法重置文件指针: {seek_error}")
                    raise Exception(f"OSS 上传失败且无法回退到本地: {str(e)}")
        
        # 本地存储模式（流式保存）
        logger.info("使用本地存储模式（流式）")
        try:
            file_path = await self._save_file_streaming(file, media_type, filename)
            
            # 获取文件大小
            full_path = self.upload_dir / file_path
            file_size = full_path.stat().st_size
            
            url = self.get_file_url(file_path)
            
            return {
                'url': url,
                'file_path': file_path,
                'size_bytes': file_size,
                'storage_mode': 'local'
            }
        except Exception as e:
            # 本地存储也失败，这是严重错误
            logger.error(f"本地流式存储失败: {e}")
            raise Exception(f"文件保存失败（OSS 和本地存储都失败）: {str(e)}")
    
    def _save_bytes_to_local(
        self,
        file_bytes: bytes,
        media_type: str,
        filename: str
    ) -> str:
        """
        保存字节到本地文件系统
        
        Args:
            file_bytes: 文件字节内容
            media_type: 媒体类型
            filename: 原始文件名
        
        Returns:
            str: 相对路径
        """
        # 生成目录和文件名
        now = datetime.now()
        year = now.strftime("%Y")
        month = now.strftime("%m")
        day = now.strftime("%d")
        
        file_dir = self.upload_dir / year / month / day / media_type
        file_dir.mkdir(parents=True, exist_ok=True)
        
        file_extension = Path(filename).suffix if filename else ""
        unique_filename = f"{uuid.uuid4()}{file_extension}"
        file_path = file_dir / unique_filename
        
        # 写入文件
        with open(file_path, "wb") as f:
            f.write(file_bytes)
        
        # 返回相对路径
        relative_path = file_path.relative_to(self.upload_dir)
        return relative_path.as_posix()
    
    async def _save_file_streaming(
        self,
        file: UploadFile,
        media_type: str,
        filename: str
    ) -> str:
        """
        流式保存文件到本地（避免内存溢出）
        
        Args:
            file: FastAPI UploadFile 对象
            media_type: 媒体类型
            filename: 原始文件名
        
        Returns:
            str: 相对路径
        """
        # 生成目录和文件名
        now = datetime.now()
        year = now.strftime("%Y")
        month = now.strftime("%m")
        day = now.strftime("%d")
        
        file_dir = self.upload_dir / year / month / day / media_type
        file_dir.mkdir(parents=True, exist_ok=True)
        
        file_extension = Path(filename).suffix if filename else ""
        unique_filename = f"{uuid.uuid4()}{file_extension}"
        file_path = file_dir / unique_filename
        
        # 流式写入文件（分块读取）
        chunk_size = 1024 * 1024  # 1MB chunks
        with open(file_path, "wb") as f:
            while True:
                chunk = await file.read(chunk_size)
                if not chunk:
                    break
                f.write(chunk)
        
        # 返回相对路径
        relative_path = file_path.relative_to(self.upload_dir)
        return relative_path.as_posix()
