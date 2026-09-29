"""
Pytest 配置和共享 Fixtures

提供测试所需的Mock对象和通用fixtures
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from datetime import date, datetime


# ============================================================================
# 数据库相关 Fixtures
# ============================================================================

@pytest.fixture
def mock_db_session():
    """Mock 数据库会话"""
    session = AsyncMock()
    session.execute = AsyncMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.refresh = AsyncMock()
    session.flush = AsyncMock()
    session.add = MagicMock()
    return session


@pytest.fixture
def mock_diary():
    """Mock 日记对象"""
    diary = MagicMock()
    diary.id = 1
    diary.user_id = 1
    diary.title = "测试日记"
    diary.content = "今天天气很好，心情愉快。"
    diary.diary_date = date(2026, 1, 18)
    diary.weather = "晴"
    diary.location = "北京"
    diary.mood_score = 80
    diary.mood_type = "开心"
    diary.is_private = 1
    diary.is_extracted = 0
    diary.is_highlight = 0
    diary.is_little_joy = 0
    diary.word_count = 15
    diary.created_at = datetime.now()
    diary.updated_at = datetime.now()
    diary.media_items = []
    return diary


@pytest.fixture
def mock_user():
    """Mock 用户对象"""
    user = MagicMock()
    user.id = 1
    user.username = "testuser"
    user.password = "hashed_password"
    user.nickname = "测试用户"
    user.avatar = None
    user.phone = "13800138000"
    user.email = "test@example.com"
    user.gender = 1
    user.birthday = date(2000, 1, 1)
    user.status = 1
    user.created_at = datetime.now()
    user.updated_at = datetime.now()
    return user


@pytest.fixture
def mock_media_item():
    """Mock 媒体项对象"""
    media = MagicMock()
    media.id = 1
    media.diary_id = 1
    media.asset_id = None
    media.media_type = "text"
    media.content = "测试文本内容"
    media.media_url = None
    media.thumbnail_url = None
    media.duration = None
    media.file_size = 100
    media.sort_order = 0
    media.created_at = datetime.now()
    return media


# ============================================================================
# 外部服务 Mock Fixtures
# ============================================================================

@pytest.fixture
def mock_volcengine_client():
    """Mock 火山引擎客户端"""
    client = MagicMock()
    client.text_to_image = AsyncMock(return_value="https://example.com/image.png")
    client.image_to_image = AsyncMock(return_value="https://example.com/enhanced.png")
    client.create_video_task = AsyncMock(return_value="task_12345")
    client.get_video_task_status = AsyncMock(return_value={
        "task_id": "task_12345",
        "status": "succeeded",
        "video_url": "https://example.com/video.mp4"
    })
    return client


@pytest.fixture
def mock_external_api_client():
    """Mock 外部API客户端（百度ASR/NLP/千帆）"""
    client = MagicMock()
    client.is_asr_configured = MagicMock(return_value=True)
    client.is_llm_configured = MagicMock(return_value=True)
    client.is_voice_emotion_configured = MagicMock(return_value=True)
    client.speech_to_text = AsyncMock(return_value="转录的文本内容")
    client.optimize_text = AsyncMock(return_value="优化后的文本")
    client.analyze_text_emotion = AsyncMock(return_value={
        "emotion_type": "积极",
        "score": 75,
        "confidence": 0.85
    })
    return client


# ============================================================================
# 请求数据 Fixtures
# ============================================================================

@pytest.fixture
def sample_create_diary_data():
    """创建日记请求数据"""
    return {
        "user_id": 1,
        "title": "测试日记",
        "content": "今天是美好的一天",
        "diary_date": "2026-01-18",
        "weather": "晴",
        "location": "北京",
        "mood_score": 80
    }


@pytest.fixture
def sample_register_data():
    """注册请求数据"""
    return {
        "username": "newuser",
        "password": "password123",
        "nickname": "新用户"
    }


@pytest.fixture
def sample_login_data():
    """登录请求数据"""
    return {
        "username": "testuser",
        "password": "password123"
    }


# ============================================================================
# 文件 Fixtures
# ============================================================================

@pytest.fixture
def mock_audio_file():
    """Mock 音频文件"""
    from io import BytesIO
    content = b"fake audio content"
    file = MagicMock()
    file.filename = "test.wav"
    file.content_type = "audio/wav"
    file.read = AsyncMock(return_value=content)
    file.seek = AsyncMock()
    return file


@pytest.fixture
def mock_image_file():
    """Mock 图片文件"""
    from io import BytesIO
    content = b"fake image content"
    file = MagicMock()
    file.filename = "test.jpg"
    file.content_type = "image/jpeg"
    file.read = AsyncMock(return_value=content)
    file.seek = AsyncMock()
    return file
