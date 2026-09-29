"""
媒体工具函数

提供媒体类型推断、文件验证等工具函数
"""
from typing import Optional
from pathlib import Path


def infer_media_type(content_type: str, file_extension: str) -> Optional[str]:
    """
    根据 MIME 类型和文件扩展名推断媒体类型
    
    重要：不信任客户端提供的类型，由服务端根据 content_type 和扩展名推断
    
    Args:
        content_type: MIME 类型（如 audio/m4a, image/jpeg）
        file_extension: 文件扩展名（含点，如 .jpg, .m4a）
    
    Returns:
        str: 媒体类型（image/audio/video）或 None（不支持的类型）
    
    验证需求: 8.1
    
    Examples:
        >>> infer_media_type("audio/m4a", ".m4a")
        'audio'
        >>> infer_media_type("image/jpeg", ".jpg")
        'image'
        >>> infer_media_type("video/mp4", ".mp4")
        'video'
        >>> infer_media_type("application/pdf", ".pdf")
        None
    """
    # MIME 类型前缀映射
    mime_mapping = {
        'image/': 'image',
        'audio/': 'audio',
        'video/': 'video'
    }
    
    # 文件扩展名白名单（服务端信任的格式）
    extension_mapping = {
        'image': {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp'},
        'audio': {'.m4a', '.mp3', '.wav', '.aac', '.ogg'},
        'video': {'.mp4', '.mov', '.avi', '.mkv', '.webm'}
    }
    
    # 规范化扩展名（转小写）
    file_extension = file_extension.lower() if file_extension else ""
    
    # 1. 根据 MIME 类型推断
    inferred_type = None
    for prefix, media_type in mime_mapping.items():
        if content_type.startswith(prefix):
            inferred_type = media_type
            break
    
    # 2. 验证扩展名是否在白名单中
    if inferred_type and file_extension in extension_mapping[inferred_type]:
        return inferred_type
    
    # 3. 如果 MIME 类型不可信或不匹配，尝试仅根据扩展名推断
    # （某些客户端可能发送错误的 MIME 类型）
    for media_type, extensions in extension_mapping.items():
        if file_extension in extensions:
            return media_type
    
    # 4. 都不匹配，返回 None（不支持的类型）
    return None


def get_supported_extensions(media_type: str) -> set:
    """
    获取指定媒体类型支持的文件扩展名列表
    
    Args:
        media_type: 媒体类型（image/audio/video）
    
    Returns:
        set: 支持的扩展名集合
    
    Examples:
        >>> get_supported_extensions("audio")
        {'.m4a', '.mp3', '.wav', '.aac', '.ogg'}
    """
    extension_mapping = {
        'image': {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp'},
        'audio': {'.m4a', '.mp3', '.wav', '.aac', '.ogg'},
        'video': {'.mp4', '.mov', '.avi', '.mkv', '.webm'}
    }
    
    return extension_mapping.get(media_type, set())


def is_supported_file(filename: str, media_type: Optional[str] = None) -> bool:
    """
    检查文件是否为支持的格式
    
    Args:
        filename: 文件名
        media_type: 可选的媒体类型限制（如果提供，只检查该类型）
    
    Returns:
        bool: 是否支持
    
    Examples:
        >>> is_supported_file("test.m4a")
        True
        >>> is_supported_file("test.m4a", "audio")
        True
        >>> is_supported_file("test.m4a", "image")
        False
        >>> is_supported_file("test.pdf")
        False
    """
    if not filename:
        return False
    
    file_extension = Path(filename).suffix.lower()
    
    if media_type:
        # 检查特定类型
        supported_extensions = get_supported_extensions(media_type)
        return file_extension in supported_extensions
    else:
        # 检查所有类型
        all_extensions = set()
        for extensions in [
            get_supported_extensions('image'),
            get_supported_extensions('audio'),
            get_supported_extensions('video')
        ]:
            all_extensions.update(extensions)
        return file_extension in all_extensions
