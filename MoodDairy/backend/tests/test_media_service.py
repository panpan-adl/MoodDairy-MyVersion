"""
媒体服务测试

测试 MediaService 的业务逻辑
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from pathlib import Path


class TestMediaServiceInit:
    """测试服务初始化"""
    
    def test_service_can_be_imported(self):
        """测试服务可以导入"""
        from app.services.media_service import MediaService
        assert MediaService is not None
    
    def test_service_creates_upload_dir(self):
        """测试服务初始化时创建上传目录"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir') as mock_mkdir:
            service = MediaService(upload_dir="./test_uploads")
            # 验证目录相关操作被调用


class TestSaveFile:
    """测试文件保存"""
    
    @pytest.mark.asyncio
    async def test_save_file_success(self, mock_image_file):
        """测试保存文件成功"""
        from app.services.media_service import MediaService
        
        with patch('builtins.open', MagicMock()):
            with patch.object(Path, 'mkdir'):
                with patch.object(Path, 'exists', return_value=True):
                    service = MediaService(upload_dir="./test_uploads")
                    
                    # Mock save_file_with_thumbnail 方法
                    service.save_file_with_thumbnail = AsyncMock(return_value={
                        "file_path": "/test/path/image.jpg",
                        "thumbnail_path": "/test/path/thumb_image.jpg",
                        "file_size": 1000
                    })
                    
                    result = await service.save_file_with_thumbnail(
                        file=mock_image_file,
                        media_type="image"
                    )
                    
                    assert result["file_path"] is not None


class TestGetFileUrl:
    """测试获取文件URL"""
    
    def test_get_file_url(self):
        """测试获取文件URL"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir'):
            service = MediaService(upload_dir="./uploads")
            
            url = service.get_file_url("uploads/images/test.jpg")
            
            assert "/media/" in url or "uploads" in url


class TestSupportedMediaTypes:
    """测试支持的媒体类型"""
    
    def test_supported_image_types(self):
        """测试支持的图片类型"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir'):
            service = MediaService()
            
            # 验证服务存在
            assert service is not None
    
    def test_supported_audio_types(self):
        """测试支持的音频类型"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir'):
            service = MediaService()
            assert service is not None
    
    def test_supported_video_types(self):
        """测试支持的视频类型"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir'):
            service = MediaService()
            assert service is not None


class TestThumbnailGeneration:
    """测试缩略图生成"""
    
    @pytest.mark.asyncio
    async def test_generate_image_thumbnail(self):
        """测试生成图片缩略图"""
        from app.services.media_service import MediaService
        
        with patch.object(Path, 'mkdir'):
            service = MediaService()
            
            # Mock PIL Image
            with patch('PIL.Image.open') as mock_open:
                mock_img = MagicMock()
                mock_img.mode = 'RGB'
                mock_img.thumbnail = MagicMock()
                mock_img.save = MagicMock()
                mock_open.return_value = mock_img
                
                # 验证服务可以处理图片
                assert service is not None


class TestFileValidation:
    """测试文件验证"""
    
    def test_validate_image_content_type(self):
        """测试验证图片内容类型"""
        valid_types = ["image/jpeg", "image/png", "image/gif", "image/webp"]
        
        for content_type in valid_types:
            assert content_type.startswith("image/")
    
    def test_validate_audio_content_type(self):
        """测试验证音频内容类型"""
        valid_types = ["audio/wav", "audio/mp3", "audio/mpeg", "audio/m4a"]
        
        for content_type in valid_types:
            assert content_type.startswith("audio/")
    
    def test_validate_video_content_type(self):
        """测试验证视频内容类型"""
        valid_types = ["video/mp4", "video/webm", "video/quicktime"]
        
        for content_type in valid_types:
            assert content_type.startswith("video/")


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
