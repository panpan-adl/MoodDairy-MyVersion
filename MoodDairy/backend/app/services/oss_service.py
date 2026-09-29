"""
OSS 服务层

提供阿里云 OSS 文件上传功能
"""
import oss2
import uuid
import logging
import mimetypes
from datetime import datetime
from typing import Dict, BinaryIO, Any
from pathlib import Path
from urllib.parse import urlparse, urlunparse, unquote

logger = logging.getLogger(__name__)


def _normalize_audio_content_type(file_extension: str, content_type: str) -> str:
    """
    规范化音频 Content-Type
    
    Args:
        file_extension: 文件扩展名（如 .m4a 或 m4a）
        content_type: 原始 MIME 类型
    
    Returns:
        str: 规范化的 Content-Type
    """
    ext = (file_extension or "").lower().strip()
    # 统一 ext 形态：保证以 . 开头
    if ext and not ext.startswith("."):
        ext = "." + ext
    
    # 如果传进来是 audio/* 或空，就按后缀推断
    if not content_type or content_type.strip().lower() in ("audio/*", "application/octet-stream"):
        mapping = {
            ".m4a": "audio/mp4",
            ".mp4": "audio/mp4",
            ".mp3": "audio/mpeg",
            ".wav": "audio/wav",
            ".ogg": "audio/ogg",
            ".flac": "audio/flac",
            ".webm": "audio/webm",
            ".aac": "audio/aac",
        }
        if ext in mapping:
            return mapping[ext]
        
        guessed, _ = mimetypes.guess_type("file" + ext)
        return guessed or "audio/mp4"
    
    # 传进来如果是 audio/xxx 就用它；但 audio/* 不行
    if content_type.strip().lower() == "audio/*":
        return "audio/mp4"
    
    return content_type


class OSSService:
    """阿里云 OSS 上传服务"""
    
    def __init__(
        self,
        endpoint: str,
        bucket_name: str,
        access_key_id: str,
        access_key_secret: str,
        public_base_url: str,
        prefix: str = "media/"
    ):
        """
        初始化 OSS 服务
        
        Args:
            endpoint: OSS API 端点（如 oss-cn-hangzhou.aliyuncs.com）
            bucket_name: 存储桶名称
            access_key_id: 访问密钥 ID
            access_key_secret: 访问密钥 Secret
            public_base_url: 公共访问基础 URL
            prefix: 对象键前缀（默认 media/）
        """
        try:
            auth = oss2.Auth(access_key_id, access_key_secret)
            self.bucket = oss2.Bucket(auth, endpoint, bucket_name)
            self.bucket_name = bucket_name
            self.public_base_url = public_base_url.rstrip('/')
            self.prefix = prefix
            
            # 检查 bucket ACL（用于判断是否可以使用公共 URL）
            self._bucket_acl = None
            try:
                acl_result = self.bucket.get_bucket_acl()
                self._bucket_acl = acl_result.acl
                logger.info(f"OSS bucket ACL: {self._bucket_acl}")
            except Exception as e:
                logger.warning(f"无法获取 bucket ACL: {e}")
            
            logger.info(f"OSS 服务初始化成功: bucket={bucket_name}, endpoint={endpoint}")
        except Exception as e:
            logger.error(f"OSS 服务初始化失败: {e}")
            raise
    
    def is_bucket_public_read(self) -> bool:
        """
        检查 bucket 是否为公共读
        
        Returns:
            bool: 如果 bucket 是 public-read，返回 True
        """
        return self._bucket_acl == 'public-read'
    
    def upload_bytes(
        self,
        file_bytes: bytes,
        content_type: str,
        file_extension: str
    ) -> Dict[str, Any]:
        """
        上传文件字节到 OSS（适用于小文件 <50MB）
        
        Args:
            file_bytes: 文件字节内容
            content_type: MIME 类型
            file_extension: 文件扩展名（如 .m4a）
        
        Returns:
            Dict: {
                'url': 公共访问 URL,
                'bucket': 存储桶名称,
                'key': 对象键,
                'size_bytes': 文件大小
            }
        
        Raises:
            oss2.exceptions.OssError: OSS 上传失败
            Exception: 其他上传错误
        """
        key = None
        try:
            # 生成唯一键: media/YYYY/MM/DD/{uuid}.{ext}
            key = self._generate_key(file_extension)
            
            # 规范化 Content-Type
            normalized_ct = _normalize_audio_content_type(file_extension, content_type)
            
            # ✅ 显式覆盖：避免 attachment / force-download 影响百度抓取
            headers = {
                "Content-Type": normalized_ct,
                "Content-Disposition": "inline",  # 关键：别用 attachment
            }
            
            # 上传到 OSS
            result = self.bucket.put_object(key, file_bytes, headers=headers)
            
            # 验证上传成功
            if result.status != 200:
                error_msg = f"OSS 上传失败: HTTP {result.status}"
                logger.error(f"{error_msg}, key={key}, size={len(file_bytes)} bytes")
                raise Exception(error_msg)
            
            # 生成公共 URL
            url = f"{self.public_base_url}/{key}"
            
            logger.info(f"文件上传成功: key={key}, size={len(file_bytes)} bytes, content_type={normalized_ct}")
            
            return {
                "url": url,
                "bucket": self.bucket_name,
                "key": key,
                "size_bytes": len(file_bytes)
            }
        except oss2.exceptions.OssError as e:
            # 捕获 OSS 特定错误，记录详细信息
            logger.error(
                f"OSS 上传失败 - 错误码: {e.code}, 消息: {e.message}, "
                f"请求ID: {e.request_id}, key: {key}, "
                f"bucket: {self.bucket_name}, size: {len(file_bytes)} bytes"
            )
            # 重新抛出 OssError，让上层决定是否回退
            raise
        except Exception as e:
            logger.error(f"文件上传失败: {e}, key={key}")
            raise
    
    def upload_file_object(
        self,
        file_object: BinaryIO,
        content_type: str,
        file_extension: str
    ) -> Dict[str, any]:
        """
        上传文件对象到 OSS（流式上传，适用于大文件 >50MB）
        
        重要：此方法避免将整个文件读入内存，适用于 200MB 大文件场景
        
        Args:
            file_object: 文件对象（如 SpooledTemporaryFile）
            content_type: MIME 类型
            file_extension: 文件扩展名
        
        Returns:
            Dict: 同 upload_bytes
        
        Raises:
            oss2.exceptions.OssError: OSS 上传失败
            Exception: 其他上传错误
        """
        key = None
        file_size = 0
        try:
            # 生成唯一键
            key = self._generate_key(file_extension)
            
            # 获取文件大小（不读取整个文件）
            file_object.seek(0, 2)  # 移动到文件末尾
            file_size = file_object.tell()
            file_object.seek(0)  # 重置到开头
            
            # 流式上传到 OSS
            headers = {'Content-Type': content_type}
            result = self.bucket.put_object(key, file_object, headers=headers)
            
            # 验证上传成功
            if result.status != 200:
                error_msg = f"OSS 流式上传失败: HTTP {result.status}"
                logger.error(f"{error_msg}, key={key}, size={file_size} bytes")
                raise Exception(error_msg)
            
            # 生成公共 URL
            url = f"{self.public_base_url}/{key}"
            
            logger.info(f"大文件流式上传成功: key={key}, size={file_size} bytes")
            
            return {
                'url': url,
                'bucket': self.bucket_name,
                'key': key,
                'size_bytes': file_size
            }
        except oss2.exceptions.OssError as e:
            # 捕获 OSS 特定错误，记录详细信息
            logger.error(
                f"OSS 流式上传失败 - 错误码: {e.code}, 消息: {e.message}, "
                f"请求ID: {e.request_id}, key: {key}, "
                f"bucket: {self.bucket_name}, size: {file_size} bytes"
            )
            # 重新抛出 OssError，让上层决定是否回退
            raise
        except Exception as e:
            logger.error(f"大文件上传失败: {e}, key={key}, size={file_size} bytes")
            raise
    
    def _generate_key(self, file_extension: str) -> str:
        """
        生成 OSS 对象键
        
        格式: media/YYYY/MM/DD/{uuid}.{ext}
        
        Args:
            file_extension: 文件扩展名
        
        Returns:
            str: 对象键
        """
        now = datetime.now()
        date_path = now.strftime("%Y/%m/%d")
        unique_id = str(uuid.uuid4())
        
        # 确保扩展名以点开头
        if file_extension and not file_extension.startswith('.'):
            file_extension = f".{file_extension}"
        
        key = f"{self.prefix}{date_path}/{unique_id}{file_extension}"
        return key
    
    def generate_signed_url_for_baidu_asr(
        self,
        key: str,
        expires_seconds: int = 24 * 60 * 60,  # 建议至少 24h（异步转写可能比较久）
        content_type: str = "audio/mp4",
        use_public_url: bool = None,  # None 表示自动检测
    ) -> str:
        """
        生成给百度 ASR 用的签名 GET URL：
        - 强制 response-content-type 为明确音频类型
        - 强制 response-content-disposition 为 inline（避免附件下载语义）
        
        Args:
            key: OSS 对象键
            expires_seconds: 签名有效期（秒），默认 24 小时
            content_type: 响应的 Content-Type，默认 audio/mp4
            use_public_url: 如果为 True，直接返回公共 URL；如果为 None，自动检测 bucket ACL
        
        Returns:
            str: 签名后的完整 URL
        """
        # 自动检测是否使用公共 URL
        if use_public_url is None:
            use_public_url = self.is_bucket_public_read()
            if use_public_url:
                logger.info("Bucket is public-read, using public URL")
            else:
                logger.info(f"Bucket ACL is {self._bucket_acl}, using signed URL")
        
        # 如果使用公共 URL（bucket 是公共读的），直接返回公共 URL
        if use_public_url:
            public_url = f"{self.public_base_url}/{key}"
            logger.info(f"Using public URL for Baidu ASR (no signature needed): {public_url}")
            return public_url
        
        # 生成签名 URL（适用于私有 bucket）
        # 注意：百度 ASR 可能需要简单的签名 URL，不包含 response-content-type 等参数
        # 先尝试不带参数的签名 URL
        try:
            # 方法1：不带参数的签名 URL（更简单，可能更兼容）
            signed_url = self.bucket.sign_url(
                "GET",
                key,
                expires_seconds
            )
            logger.info("Generated signed URL without response headers")
        except Exception as e:
            logger.warning(f"Failed to generate simple signed URL: {e}, trying with params")
            # 方法2：带参数的签名 URL（如果方法1失败）
            params = {
                "response-content-type": content_type,
                "response-content-disposition": "inline",
            }
            signed_url = self.bucket.sign_url(
                "GET",
                key,
                expires_seconds,
                params=params
            )
        
        # 修复 URL 编码问题：将路径部分的 %2F 还原为 /
        # 百度 ASR 可能无法正确处理编码后的 URL，所以我们需要手动解码路径部分
        if '%2F' in signed_url:
            # 使用 urllib.parse 解析和修复 URL
            parsed = urlparse(signed_url)
            # 解码路径部分（将 %2F 还原为 /）
            decoded_path = unquote(parsed.path)
            # 重新构建 URL（保留其他部分不变）
            fixed_url = urlunparse((
                parsed.scheme,
                parsed.netloc,
                decoded_path,
                parsed.params,
                parsed.query,
                parsed.fragment
            ))
            signed_url = fixed_url
            logger.info(f"Fixed URL encoding: decoded %2F to / in path (before: {parsed.path}, after: {decoded_path})")
        
        return signed_url

    def generate_presigned_get_url(self, key: str, expires: int = 3600) -> str:
        """Generate a time-limited GET URL for private bucket objects."""
        if self.is_bucket_public_read():
            return f"{self.public_base_url}/{key}"
        return self.bucket.sign_url("GET", key, expires)
