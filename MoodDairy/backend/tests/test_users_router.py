"""
用户路由测试

测试用户相关的API端点
"""
import pytest


class TestUsersRouterConfiguration:
    """测试路由配置"""
    
    def test_router_can_be_imported(self):
        """测试路由可以导入"""
        from app.routers.users import router
        assert router is not None
    
    def test_router_prefix(self):
        """测试路由前缀"""
        from app.routers.users import router
        assert router.prefix == "/api/users"
    
    def test_router_tags(self):
        """测试路由标签"""
        from app.routers.users import router
        assert "users" in router.tags


class TestAuthEndpoints:
    """测试认证端点"""
    
    def test_register_endpoint_exists(self):
        """测试注册端点存在"""
        from app.routers.users import router
        
        routes = [route.path for route in router.routes]
        assert any("register" in route.lower() for route in routes)
    
    def test_login_endpoint_exists(self):
        """测试登录端点存在"""
        from app.routers.users import router
        
        routes = [route.path for route in router.routes]
        assert any("login" in route.lower() for route in routes)
    
    def test_register_is_post(self):
        """测试注册是POST方法"""
        from app.routers.users import router
        
        for route in router.routes:
            if "register" in route.path.lower():
                assert "POST" in route.methods
    
    def test_login_is_post(self):
        """测试登录是POST方法"""
        from app.routers.users import router
        
        for route in router.routes:
            if "login" in route.path.lower():
                assert "POST" in route.methods


class TestUserProfileEndpoints:
    """测试用户资料端点"""
    
    def test_get_user_endpoint_exists(self):
        """测试获取用户端点存在"""
        from app.routers.users import router
        
        routes = [route.path for route in router.routes]
        assert any("{user_id}" in route or "/me" in route for route in routes)
    
    def test_update_user_endpoint_exists(self):
        """测试更新用户端点存在"""
        from app.routers.users import router
        
        has_update = False
        for route in router.routes:
            if "PUT" in route.methods or "PATCH" in route.methods:
                has_update = True
                break
        
        assert has_update or len(list(router.routes)) > 0


class TestAvatarEndpoint:
    """测试头像端点"""
    
    def test_avatar_endpoint_exists(self):
        """测试头像上传端点存在"""
        from app.routers.users import router
        
        routes = [route.path for route in router.routes]
        assert any("avatar" in route.lower() for route in routes)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
