"""
画作服务测试

测试DrawingService的业务逻辑
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch


class TestDrawingServiceInit:
    """测试服务初始化"""
    
    def test_service_can_be_imported(self):
        """测试服务可以导入"""
        from app.drawing.drawing_service import DrawingService, DrawingServiceError
        assert DrawingService is not None
        assert DrawingServiceError is not None
    
    def test_style_prompts_defined(self):
        """测试画风提示词已定义"""
        from app.drawing.drawing_service import DrawingService
        
        assert "watercolor" in DrawingService.STYLE_PROMPTS
        assert "oil" in DrawingService.STYLE_PROMPTS
        assert "cartoon" in DrawingService.STYLE_PROMPTS
        assert "realistic" in DrawingService.STYLE_PROMPTS
        assert "anime" in DrawingService.STYLE_PROMPTS
        assert "sketch" in DrawingService.STYLE_PROMPTS
    
    def test_enhance_base_prompt_defined(self):
        """测试增强基础提示词已定义"""
        from app.drawing.drawing_service import DrawingService
        
        assert len(DrawingService.ENHANCE_BASE_PROMPT) > 0
        assert "简笔画" in DrawingService.ENHANCE_BASE_PROMPT


class TestGenerateFromText:
    """测试文字生成画作"""
    
    @pytest.mark.asyncio
    async def test_generate_from_text_builds_correct_prompt(self):
        """测试生成正确的提示词"""
        from app.drawing.drawing_service import DrawingService
        
        mock_client = MagicMock()
        mock_client.text_to_image = AsyncMock(return_value="https://example.com/result.png")
        
        service = DrawingService(client=mock_client)
        
        result = await service.generate_from_text(
            prompt="美丽的日落",
            style="watercolor"
        )
        
        assert result["image_url"] == "https://example.com/result.png"
        assert "美丽的日落" in result["prompt_used"]
        assert "水彩" in result["prompt_used"]
        
        mock_client.text_to_image.assert_called_once()
    
    @pytest.mark.asyncio
    async def test_generate_from_text_with_different_styles(self):
        """测试不同画风"""
        from app.drawing.drawing_service import DrawingService
        
        mock_client = MagicMock()
        mock_client.text_to_image = AsyncMock(return_value="https://example.com/result.png")
        
        service = DrawingService(client=mock_client)
        
        result = await service.generate_from_text(prompt="风景", style="oil")
        assert "油画" in result["prompt_used"]
        
        mock_client.text_to_image.reset_mock()
        result = await service.generate_from_text(prompt="风景", style="cartoon")
        assert "卡通" in result["prompt_used"]
    
    @pytest.mark.asyncio
    async def test_generate_from_text_error_handling(self):
        """测试错误处理"""
        from app.drawing.drawing_service import DrawingService, DrawingServiceError
        from app.drawing.volcengine_client import VolcengineAPIError
        
        mock_client = MagicMock()
        mock_client.text_to_image = AsyncMock(side_effect=VolcengineAPIError("API错误"))
        
        service = DrawingService(client=mock_client)
        
        with pytest.raises(DrawingServiceError) as exc_info:
            await service.generate_from_text(prompt="测试")
        
        assert "生成画作失败" in str(exc_info.value)


class TestEnhanceSketch:
    """测试简笔画增强"""
    
    @pytest.mark.asyncio
    async def test_enhance_sketch_success(self):
        """测试增强简笔画成功"""
        from app.drawing.drawing_service import DrawingService
        
        mock_client = MagicMock()
        mock_client.image_to_image = AsyncMock(return_value="https://example.com/enhanced.png")
        
        service = DrawingService(client=mock_client)
        
        result = await service.enhance_sketch(
            image_url="https://example.com/sketch.png",
            enhancement_prompt="添加更多细节"
        )
        
        assert result["image_url"] == "https://example.com/enhanced.png"
        assert "简笔画" in result["prompt_used"]
        assert "添加更多细节" in result["prompt_used"]
    
    @pytest.mark.asyncio
    async def test_enhance_sketch_without_extra_prompt(self):
        """测试不带额外提示词的增强"""
        from app.drawing.drawing_service import DrawingService
        
        mock_client = MagicMock()
        mock_client.image_to_image = AsyncMock(return_value="https://example.com/enhanced.png")
        
        service = DrawingService(client=mock_client)
        
        result = await service.enhance_sketch(
            image_url="https://example.com/sketch.png"
        )
        
        assert result["image_url"] == "https://example.com/enhanced.png"
        assert "简笔画" in result["prompt_used"]


class TestBuildDiaryPrompt:
    """测试日记提示词构建"""
    
    def test_build_diary_prompt_with_emotion_transform(self):
        """测试带情绪转换的提示词"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        prompt = service._build_diary_prompt(
            content="今天心情不好，下雨了",
            style="watercolor",
            include_emotion_transform=True
        )
        
        assert "今天心情不好" in prompt
        assert "治愈" in prompt or "希望" in prompt or "积极" in prompt
        assert "水彩" in prompt
    
    def test_build_diary_prompt_without_emotion_transform(self):
        """测试不带情绪转换的提示词"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        prompt = service._build_diary_prompt(
            content="今天心情不好",
            style="oil",
            include_emotion_transform=False
        )
        
        assert "今天心情不好" in prompt
        assert "忠实还原" in prompt
        assert "油画" in prompt
    
    def test_build_diary_prompt_truncates_long_content(self):
        """测试长内容被截断"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        long_content = "测试内容" * 200
        prompt = service._build_diary_prompt(
            content=long_content,
            style="watercolor",
            include_emotion_transform=True
        )
        
        assert len(prompt) < len(long_content) + 500


class TestExtractDiaryText:
    """测试日记文本提取"""
    
    def test_extract_from_title_and_content(self):
        """测试从标题和内容提取"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        mock_diary = MagicMock()
        mock_diary.title = "今日心情"
        mock_diary.content = "今天天气很好"
        mock_diary.media_items = []
        
        result = service._extract_diary_text(mock_diary)
        
        assert "今日心情" in result
        assert "今天天气很好" in result
    
    def test_extract_with_media_items(self):
        """测试从媒体项提取文本"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        mock_text_item = MagicMock()
        mock_text_item.media_type = "text"
        mock_text_item.content = "语音转录的文本"
        
        mock_image_item = MagicMock()
        mock_image_item.media_type = "image"
        mock_image_item.content = None
        
        mock_diary = MagicMock()
        mock_diary.title = "测试"
        mock_diary.content = None
        mock_diary.media_items = [mock_text_item, mock_image_item]
        
        result = service._extract_diary_text(mock_diary)
        
        assert "语音转录的文本" in result
    
    def test_extract_empty_diary(self):
        """测试空日记"""
        from app.drawing.drawing_service import DrawingService
        
        service = DrawingService(client=MagicMock())
        
        mock_diary = MagicMock()
        mock_diary.title = None
        mock_diary.content = None
        mock_diary.media_items = []
        
        result = service._extract_diary_text(mock_diary)
        
        assert result == ""


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
