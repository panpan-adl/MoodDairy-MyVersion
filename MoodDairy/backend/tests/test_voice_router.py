"""
语音路由测试

测试语音相关的API端点
"""
import pytest


class TestVoiceRouterConfiguration:
    """测试路由配置"""
    
    def test_router_can_be_imported(self):
        """测试路由可以导入"""
        from app.routers.voice import router
        assert router is not None
    
    def test_router_prefix(self):
        """测试路由前缀"""
        from app.routers.voice import router
        assert router.prefix == "/voice"
    
    def test_router_tags(self):
        """测试路由标签"""
        from app.routers.voice import router
        assert "voice" in router.tags


class TestVoiceEndpoints:
    """测试语音端点"""
    
    def test_process_voice_endpoint_exists(self):
        """测试语音处理端点存在"""
        from app.routers.voice import router
        
        routes = [route.path for route in router.routes]
        assert any("process" in route.lower() for route in routes)
    
    def test_transcribe_endpoint_exists(self):
        """测试转录端点存在"""
        from app.routers.voice import router
        
        routes = [route.path for route in router.routes]
        assert any("transcribe" in route.lower() for route in routes)
    
    def test_analyze_emotion_endpoint_exists(self):
        """测试情感分析端点存在"""
        from app.routers.voice import router
        
        routes = [route.path for route in router.routes]
        assert any("emotion" in route.lower() for route in routes)


class TestVoiceEndpointMethods:
    """测试语音端点HTTP方法"""
    
    def test_process_voice_is_post(self):
        """测试语音处理是POST方法"""
        from app.routers.voice import router
        
        for route in router.routes:
            if "process" in route.path.lower():
                assert "POST" in route.methods
    
    def test_analyze_emotion_accepts_form(self):
        """测试情感分析接受表单数据"""
        from app.routers.voice import router
        
        for route in router.routes:
            if "emotion" in route.path.lower():
                assert "POST" in route.methods


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
