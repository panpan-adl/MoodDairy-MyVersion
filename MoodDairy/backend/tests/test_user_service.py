"""
用户服务测试

测试 UserService 的业务逻辑
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch


class TestUserServiceInit:
    """测试服务初始化"""
    
    def test_service_can_be_imported(self):
        """测试服务可以导入"""
        from app.services.user_service import UserService
        assert UserService is not None


class TestCreateUser:
    """测试创建用户（注册）"""
    
    @pytest.mark.asyncio
    async def test_create_user_success(self, mock_db_session, sample_register_data):
        """测试创建用户成功"""
        from app.services.user_service import UserService
        from app.models.schemas import RegisterRequest
        
        request = RegisterRequest(**sample_register_data)
        
        # Mock: 用户名不存在
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.create_user(request)
        
        mock_db_session.add.assert_called()
        mock_db_session.commit.assert_called()
    
    @pytest.mark.asyncio
    async def test_create_user_username_exists(self, mock_db_session, mock_user, sample_register_data):
        """测试创建用户 - 用户名已存在"""
        from app.services.user_service import UserService
        from app.models.schemas import RegisterRequest
        
        request = RegisterRequest(**sample_register_data)
        
        # Mock: 用户名已存在
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.create_user(request)
        
        # 返回None表示用户名已存在
        assert result is None


class TestAuthenticateUser:
    """测试用户认证（登录）"""
    
    @pytest.mark.asyncio
    async def test_authenticate_user_success(self, mock_db_session, mock_user, sample_login_data):
        """测试认证成功"""
        from app.services.user_service import UserService
        
        # 设置mock用户的密码与登录密码匹配
        mock_user.password = sample_login_data["password"]
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.authenticate_user(
            username=sample_login_data["username"],
            password=sample_login_data["password"]
        )
        
        assert result is not None
        assert result.username == mock_user.username
    
    @pytest.mark.asyncio
    async def test_authenticate_user_not_found(self, mock_db_session, sample_login_data):
        """测试认证 - 用户不存在"""
        from app.services.user_service import UserService
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.authenticate_user(
            username=sample_login_data["username"],
            password=sample_login_data["password"]
        )
        
        assert result is None
    
    @pytest.mark.asyncio
    async def test_authenticate_user_wrong_password(self, mock_db_session, mock_user, sample_login_data):
        """测试认证 - 密码错误"""
        from app.services.user_service import UserService
        
        # 设置mock用户的密码与登录密码不匹配
        mock_user.password = "wrong_password"
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.authenticate_user(
            username=sample_login_data["username"],
            password=sample_login_data["password"]
        )
        
        assert result is None


class TestGetUser:
    """测试获取用户信息"""
    
    @pytest.mark.asyncio
    async def test_get_user(self, mock_db_session, mock_user):
        """测试通过ID获取用户"""
        from app.services.user_service import UserService
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.get_user(user_id=1)
        
        assert result is not None
        assert result.id == 1
    
    @pytest.mark.asyncio
    async def test_get_user_not_found(self, mock_db_session):
        """测试获取用户 - 用户不存在"""
        from app.services.user_service import UserService
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=None)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.get_user(user_id=999)
        
        assert result is None


class TestUpdateUser:
    """测试更新用户信息"""
    
    @pytest.mark.asyncio
    async def test_update_user_success(self, mock_db_session, mock_user):
        """测试更新用户信息成功"""
        from app.services.user_service import UserService
        from app.models.schemas import UpdateUserRequest
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        
        update_data = UpdateUserRequest(nickname="新昵称")
        result = await service.update_user(user_id=1, data=update_data)
        
        mock_db_session.commit.assert_called()


class TestUpdateAvatar:
    """测试更新头像"""
    
    @pytest.mark.asyncio
    async def test_update_avatar_success(self, mock_db_session, mock_user):
        """测试更新头像成功"""
        from app.services.user_service import UserService
        
        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)
        mock_db_session.execute = AsyncMock(return_value=mock_result)
        
        service = UserService(mock_db_session)
        result = await service.update_avatar(
            user_id=1,
            avatar_url="https://example.com/avatar.jpg"
        )
        
        mock_db_session.commit.assert_called()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
