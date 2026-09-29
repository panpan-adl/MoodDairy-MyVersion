"""
核心 Schemas 测试

测试 app/models/schemas.py 中的Pydantic数据模型
"""
import pytest
from pydantic import ValidationError
from datetime import date, datetime

from app.models.schemas import (
    CreateDiaryRequest,
    UpdateDiaryRequest,
    AddMediaRequest,
    SyncMediaItemRequest,
    SyncMediaRequest,
    MediaItemResponse,
    DiaryResponse,
    EmotionResult,
    VoiceProcessingResult,
    LoginRequest,
    RegisterRequest,
    UpdateUserRequest,
    UserResponse,
    ErrorResponse,
    SuccessResponse,
)


class TestCreateDiaryRequest:
    """测试创建日记请求"""
    
    def test_valid_request(self):
        """测试有效请求"""
        request = CreateDiaryRequest(
            user_id=1,
            title="今日心情",
            content="今天天气很好",
            diary_date=date(2026, 1, 18),
            weather="晴",
            location="北京",
            mood_score=80
        )
        assert request.user_id == 1
        assert request.title == "今日心情"
        assert request.diary_date == date(2026, 1, 18)
    
    def test_required_fields(self):
        """测试必填字段"""
        request = CreateDiaryRequest(
            user_id=1,
            diary_date=date(2026, 1, 18)
        )
        assert request.user_id == 1
        assert request.title is None
        assert request.content is None
    
    def test_mood_score_range(self):
        """测试心情评分范围"""
        # 有效范围
        request = CreateDiaryRequest(
            user_id=1,
            diary_date=date(2026, 1, 18),
            mood_score=1
        )
        assert request.mood_score == 1
        
        request = CreateDiaryRequest(
            user_id=1,
            diary_date=date(2026, 1, 18),
            mood_score=100
        )
        assert request.mood_score == 100
    
    def test_mood_score_out_of_range(self):
        """测试心情评分超出范围"""
        with pytest.raises(ValidationError):
            CreateDiaryRequest(
                user_id=1,
                diary_date=date(2026, 1, 18),
                mood_score=0
            )
        
        with pytest.raises(ValidationError):
            CreateDiaryRequest(
                user_id=1,
                diary_date=date(2026, 1, 18),
                mood_score=101
            )


class TestUpdateDiaryRequest:
    """测试更新日记请求"""
    
    def test_all_fields_optional(self):
        """测试所有字段都是可选的"""
        request = UpdateDiaryRequest()
        assert request.title is None
        assert request.content is None
        assert request.weather is None
    
    def test_partial_update(self):
        """测试部分更新"""
        request = UpdateDiaryRequest(title="新标题")
        assert request.title == "新标题"
        assert request.content is None


class TestLoginRequest:
    """测试登录请求"""
    
    def test_valid_request(self):
        """测试有效请求"""
        request = LoginRequest(
            username="testuser",
            password="password123"
        )
        assert request.username == "testuser"
        assert request.password == "password123"
    
    def test_empty_username_rejected(self):
        """测试空用户名被拒绝"""
        with pytest.raises(ValidationError):
            LoginRequest(username="", password="password123")
    
    def test_short_password_rejected(self):
        """测试过短密码被拒绝"""
        with pytest.raises(ValidationError):
            LoginRequest(username="testuser", password="12345")


class TestRegisterRequest:
    """测试注册请求"""
    
    def test_valid_request(self):
        """测试有效请求"""
        request = RegisterRequest(
            username="newuser",
            password="password123",
            nickname="新用户"
        )
        assert request.username == "newuser"
        assert request.nickname == "新用户"
    
    def test_optional_nickname(self):
        """测试昵称可选"""
        request = RegisterRequest(
            username="newuser",
            password="password123"
        )
        assert request.nickname is None


class TestUpdateUserRequest:
    """测试更新用户请求"""
    
    def test_all_fields_optional(self):
        """测试所有字段可选"""
        request = UpdateUserRequest()
        assert request.nickname is None
        assert request.phone is None
        assert request.email is None
    
    def test_gender_range(self):
        """测试性别范围"""
        request = UpdateUserRequest(gender=0)
        assert request.gender == 0
        
        request = UpdateUserRequest(gender=1)
        assert request.gender == 1
        
        request = UpdateUserRequest(gender=2)
        assert request.gender == 2
    
    def test_gender_out_of_range(self):
        """测试性别超出范围"""
        with pytest.raises(ValidationError):
            UpdateUserRequest(gender=3)


class TestEmotionResult:
    """测试情感分析结果"""
    
    def test_valid_result(self):
        """测试有效结果"""
        result = EmotionResult(
            emotion_type="积极",
            score=80,
            confidence=0.85
        )
        assert result.emotion_type == "积极"
        assert result.score == 80
        assert result.confidence == 0.85
    
    def test_score_range(self):
        """测试评分范围"""
        with pytest.raises(ValidationError):
            EmotionResult(emotion_type="积极", score=0, confidence=0.5)
        
        with pytest.raises(ValidationError):
            EmotionResult(emotion_type="积极", score=101, confidence=0.5)
    
    def test_confidence_range(self):
        """测试置信度范围"""
        with pytest.raises(ValidationError):
            EmotionResult(emotion_type="积极", score=50, confidence=-0.1)
        
        with pytest.raises(ValidationError):
            EmotionResult(emotion_type="积极", score=50, confidence=1.1)


class TestSyncMediaRequest:
    """测试同步媒体请求"""
    
    def test_empty_items(self):
        """测试空媒体项列表"""
        request = SyncMediaRequest(items=[])
        assert len(request.items) == 0
    
    def test_with_items(self):
        """测试带媒体项"""
        item = SyncMediaItemRequest(
            media_type="text",
            content="测试内容",
            sort_order=0
        )
        request = SyncMediaRequest(items=[item])
        assert len(request.items) == 1
        assert request.items[0].media_type == "text"


class TestErrorResponse:
    """测试错误响应"""
    
    def test_basic_error(self):
        """测试基本错误"""
        response = ErrorResponse(
            error="ValidationError",
            detail="字段验证失败"
        )
        assert response.error == "ValidationError"
        assert response.detail == "字段验证失败"


class TestSuccessResponse:
    """测试成功响应"""
    
    def test_basic_success(self):
        """测试基本成功"""
        response = SuccessResponse(
            success=True,
            message="操作成功"
        )
        assert response.success == True
        assert response.message == "操作成功"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
