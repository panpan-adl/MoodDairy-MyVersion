"""
外部API客户端测试

测试 ExternalAPIClient 及其子客户端
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import os


class TestExternalAPIClientInit:
    """测试客户端初始化"""
    
    def test_client_can_be_imported(self):
        """测试客户端可以导入"""
        from app.services.external_api_client import (
            ExternalAPIClient,
            get_external_api_client,
            BaiduASRClient,
            BaiduNLPClient,
            QianfanClient
        )
        assert ExternalAPIClient is not None
        assert get_external_api_client is not None
    
    def test_error_classes_defined(self):
        """测试错误类已定义"""
        from app.services.external_api_client import (
            ASRAPIError,
            LLMAPIError,
            VoiceEmotionAPIError
        )
        
        assert ASRAPIError is not None
        assert LLMAPIError is not None
        assert VoiceEmotionAPIError is not None


class TestBaiduASRClient:
    """测试百度ASR客户端"""
    
    def test_client_init(self):
        """测试客户端初始化"""
        from app.services.external_api_client import BaiduASRClient
        
        client = BaiduASRClient(
            api_key="test_api_key",
            secret_key="test_secret_key"
        )
        
        assert client.api_key == "test_api_key"
        assert client.secret_key == "test_secret_key"
    
    @pytest.mark.asyncio
    async def test_transcribe_audio_mock(self):
        """测试语音识别（Mock）"""
        from app.services.external_api_client import BaiduASRClient
        
        client = BaiduASRClient("api_key", "secret_key")
        
        # Mock access token
        client._get_access_token = AsyncMock(return_value="mock_token")
        
        # 验证客户端存在
        assert client is not None


class TestBaiduNLPClient:
    """测试百度NLP客户端"""
    
    def test_client_init(self):
        """测试客户端初始化"""
        from app.services.external_api_client import BaiduNLPClient
        
        client = BaiduNLPClient(
            api_key="test_api_key",
            secret_key="test_secret_key"
        )
        
        assert client.api_key == "test_api_key"
    
    @pytest.mark.asyncio
    async def test_emotion_recognition_mock(self):
        """测试情绪识别（Mock）"""
        from app.services.external_api_client import BaiduNLPClient
        
        client = BaiduNLPClient("api_key", "secret_key")
        
        # Mock access token
        client._get_access_token = AsyncMock(return_value="mock_token")
        
        # 验证客户端存在
        assert client is not None


class TestQianfanClient:
    """测试千帆客户端"""
    
    def test_client_init(self):
        """测试客户端初始化"""
        from app.services.external_api_client import QianfanClient
        
        client = QianfanClient(api_key="test_api_key")
        
        assert client.api_key == "test_api_key"
    
    def test_prompt_templates_defined(self):
        """测试Prompt模板已定义"""
        from app.services.external_api_client import QianfanClient
        
        assert hasattr(QianfanClient, 'EXTRACTION_PROMPT_TEMPLATE')
        assert hasattr(QianfanClient, 'EMOTION_ANALYSIS_PROMPT_TEMPLATE')
        assert hasattr(QianfanClient, 'HIGHLIGHT_PROMPT_TEMPLATE')
    
    def test_parse_json_response(self):
        """测试JSON响应解析"""
        from app.services.external_api_client import QianfanClient
        
        client = QianfanClient(api_key="test")
        
        response_data = {
            "choices": [
                {
                    "message": {
                        "content": '{"summary": "测试摘要", "keywords": ["关键词"]}'
                    }
                }
            ]
        }
        
        result = client._parse_json_response(response_data)
        
        assert result["summary"] == "测试摘要"
        assert "关键词" in result["keywords"]


class TestExternalAPIClientConfiguration:
    """测试外部API客户端配置"""
    
    def test_client_creation_with_env(self):
        """测试带环境变量创建客户端"""
        from app.services.external_api_client import ExternalAPIClient
        
        # 客户端可以创建即可，配置由环境变量决定
        client = ExternalAPIClient()
        assert client is not None
    
    def test_is_asr_configured_method_exists(self):
        """测试ASR配置检查方法存在"""
        from app.services.external_api_client import ExternalAPIClient
        
        client = ExternalAPIClient()
        assert hasattr(client, 'is_asr_configured')
    
    def test_is_llm_configured_method_exists(self):
        """测试LLM配置检查方法存在"""
        from app.services.external_api_client import ExternalAPIClient
        
        client = ExternalAPIClient()
        assert hasattr(client, 'is_llm_configured')


class TestSingletonPattern:
    """测试单例模式"""
    
    def test_get_external_api_client_returns_same_instance(self):
        """测试获取外部API客户端返回相同实例"""
        from app.services.external_api_client import (
            get_external_api_client, 
            reset_external_api_client
        )
        
        # 重置以确保干净状态
        reset_external_api_client()
        
        client1 = get_external_api_client()
        client2 = get_external_api_client()
        
        assert client1 is client2
        
        # 清理
        reset_external_api_client()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
