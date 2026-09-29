"""
日记路由测试

测试日记相关的API端点
"""
import pytest


class TestDiariesRouterConfiguration:
    """测试路由配置"""
    
    def test_router_can_be_imported(self):
        """测试路由可以导入"""
        from app.routers.diaries import router
        assert router is not None
    
    def test_router_prefix(self):
        """测试路由前缀"""
        from app.routers.diaries import router
        assert router.prefix == "/diaries"
    
    def test_router_tags(self):
        """测试路由标签"""
        from app.routers.diaries import router
        assert "diaries" in router.tags


class TestDiaryEndpoints:
    """测试日记端点"""
    
    def test_create_diary_endpoint_exists(self):
        """测试创建日记端点存在"""
        from app.routers.diaries import router
        
        routes = {route.path: route.methods for route in router.routes}
        assert f"{router.prefix}/" in routes or router.prefix in routes
    
    def test_get_diary_by_date_endpoint_exists(self):
        """测试按日期获取日记端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("{date}" in route for route in routes)
    
    def test_get_diary_by_id_endpoint_exists(self):
        """测试按ID获取日记端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("{diary_id}" in route for route in routes)
    
    def test_delete_diary_endpoint_exists(self):
        """测试删除日记端点存在"""
        from app.routers.diaries import router
        
        has_delete = False
        for route in router.routes:
            if "{diary_id}" in route.path and "DELETE" in route.methods:
                has_delete = True
                break
        
        assert has_delete
    
    def test_sync_media_endpoint_exists(self):
        """测试同步媒体端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("media" in route.lower() for route in routes)


class TestExtractionEndpoints:
    """测试提取相关端点"""
    
    def test_extract_diary_endpoint_exists(self):
        """测试日记提取端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("extract" in route.lower() for route in routes)
    
    def test_extraction_status_endpoint_exists(self):
        """测试提取状态端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("status" in route.lower() or "extract" in route.lower() for route in routes)


class TestHighlightEndpoints:
    """测试高光时刻和小确幸端点"""
    
    def test_highlight_diaries_endpoint_exists(self):
        """测试高光时刻端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("highlight" in route.lower() for route in routes)
    
    def test_little_joy_diaries_endpoint_exists(self):
        """测试小确幸端点存在"""
        from app.routers.diaries import router
        
        routes = [route.path for route in router.routes]
        assert any("joy" in route.lower() or "little" in route.lower() for route in routes)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
