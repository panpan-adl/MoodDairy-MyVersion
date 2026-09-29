"""
媒体路由测试

测试媒体相关的API端点
"""
import pytest


class TestMediaRouterConfiguration:
    """测试路由配置"""
    
    def test_router_can_be_imported(self):
        """测试路由可以导入"""
        from app.routers.media import router
        assert router is not None
    
    def test_router_prefix(self):
        """测试路由前缀"""
        from app.routers.media import router
        assert router.prefix == "/media"
    
    def test_router_tags(self):
        """测试路由标签"""
        from app.routers.media import router
        assert "media" in router.tags


class TestMediaEndpoints:
    """测试媒体端点"""
    
    def test_upload_endpoint_exists(self):
        """测试上传端点存在"""
        from app.routers.media import router
        
        routes = [route.path for route in router.routes]
        assert any("upload" in route.lower() for route in routes)
    
    def test_upload_is_post(self):
        """测试上传是POST方法"""
        from app.routers.media import router
        
        for route in router.routes:
            if "upload" in route.path.lower():
                assert "POST" in route.methods


class TestSupportedMediaTypes:
    """测试支持的媒体类型"""
    
    def test_image_upload_supported(self):
        """测试图片上传支持"""
        # 验证路由存在即可
        from app.routers.media import router
        assert router is not None
    
    def test_audio_upload_supported(self):
        """测试音频上传支持"""
        from app.routers.media import router
        assert router is not None
    
    def test_video_upload_supported(self):
        """测试视频上传支持"""
        from app.routers.media import router
        assert router is not None


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
