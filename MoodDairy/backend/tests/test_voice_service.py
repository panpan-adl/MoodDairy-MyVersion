"""
语音服务测试

测试 VoiceProcessingService 的业务逻辑
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import os


class TestVoiceServiceInit:
    """测试服务初始化"""
    
    def test_service_can_be_imported(self):
        """测试服务可以导入"""
        from app.services.voice_service import VoiceProcessingService
        assert VoiceProcessingService is not None
    
    def test_supported_formats(self):
        """测试支持的音频格式"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        assert '.wav' in service.SUPPORTED_FORMATS
        assert '.mp3' in service.SUPPORTED_FORMATS
        assert '.m4a' in service.SUPPORTED_FORMATS
    
    def test_filler_words_defined(self):
        """测试填充词已定义"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        assert len(service.STANDALONE_FILLERS) > 0
        assert len(service.PHRASE_FILLERS) > 0
        assert "嗯" in service.STANDALONE_FILLERS
        assert "那个" in service.PHRASE_FILLERS


class TestValidateAudioFormat:
    """测试音频格式验证"""
    
    def test_valid_wav_format(self):
        """测试有效的WAV格式"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        assert service._validate_audio_format("test.wav") == True
    
    def test_valid_mp3_format(self):
        """测试有效的MP3格式"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        assert service._validate_audio_format("test.mp3") == True
    
    def test_invalid_format(self):
        """测试无效格式"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        assert service._validate_audio_format("test.txt") == False
        assert service._validate_audio_format("test.doc") == False


class TestRemoveFillerWords:
    """测试去除填充词"""
    
    def test_remove_standalone_fillers(self):
        """测试去除独立填充词"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        text = "嗯，今天天气很好"
        result = service.remove_filler_words(text)
        
        assert "嗯" not in result
        assert "今天天气很好" in result
    
    def test_remove_phrase_fillers(self):
        """测试去除短语填充词"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        text = "那个，我想说的是"
        result = service.remove_filler_words(text)
        
        assert "那个" not in result
    
    def test_preserve_content(self):
        """测试保留正文内容"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        text = "今天天气很好，我去公园散步了"
        result = service.remove_filler_words(text)
        
        assert "今天天气很好" in result
        assert "公园散步" in result
    
    def test_empty_text(self):
        """测试空文本"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        assert service.remove_filler_words("") == ""
        assert service.remove_filler_words(None) is None


class TestTranscribeAudio:
    """测试语音转文字"""
    
    @pytest.mark.asyncio
    async def test_transcribe_file_not_found(self):
        """测试文件不存在的情况"""
        from app.services.voice_service import VoiceProcessingService
        from app.services.external_api_client import ASRAPIError
        
        service = VoiceProcessingService()
        
        with pytest.raises(ASRAPIError) as exc_info:
            await service.transcribe_audio("/nonexistent/path.wav")
        
        assert "不存在" in str(exc_info.value)
    
    @pytest.mark.asyncio
    async def test_transcribe_invalid_format(self):
        """测试无效格式"""
        from app.services.voice_service import VoiceProcessingService
        from app.services.external_api_client import ASRAPIError
        
        with patch('os.path.exists', return_value=True):
            service = VoiceProcessingService()
            
            with pytest.raises(ASRAPIError) as exc_info:
                await service.transcribe_audio("/path/to/file.txt")
            
            assert "不支持的音频格式" in str(exc_info.value)


class TestAnalyzeEmotion:
    """测试情感分析"""
    
    @pytest.mark.asyncio
    async def test_analyze_empty_text(self):
        """测试分析空文本"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        result = await service.analyze_emotion("")
        
        assert result["emotion_type"] == "中性"
        assert result["score"] == 50
    
    @pytest.mark.asyncio
    async def test_analyze_voice_emotion_with_text(self):
        """测试基于文本的情感分析"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        # Mock external API client
        with patch.object(service, 'external_api_client') as mock_client:
            mock_client.is_voice_emotion_configured.return_value = True
            mock_client.analyze_text_emotion = AsyncMock(return_value={
                "emotion_type": "积极",
                "score": 80,
                "confidence": 0.9
            })
            
            result = await service.analyze_voice_emotion("今天心情很好！", is_text=True)
            
            assert result["emotion_type"] == "积极"
            assert result["score"] == 80


class TestProcessWithOptions:
    """测试带选项的处理"""
    
    @pytest.mark.asyncio
    async def test_invalid_save_mode(self, mock_db_session):
        """测试无效的save_mode"""
        from app.services.voice_service import VoiceProcessingService
        
        service = VoiceProcessingService()
        
        result = await service.process_with_options(
            audio_file_path="/path/to/audio.wav",
            diary_id=1,
            media_id=1,
            save_mode=5,  # 无效值
            user_id=1,
            db=mock_db_session
        )
        
        assert result["success"] == False
        assert "无效的 save_mode" in result["error"]


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
