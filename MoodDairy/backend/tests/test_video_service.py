"""
视频服务测试

测试VideoService的业务逻辑（不实际生成视频）
"""
import pytest
from unittest.mock import AsyncMock, MagicMock


class TestVideoServiceInit:
    """测试服务初始化"""
    
    def test_service_can_be_imported(self):
        """测试服务可以导入"""
        from app.drawing.video_service import VideoService, VideoServiceError
        assert VideoService is not None
        assert VideoServiceError is not None
    
    def test_transformation_prompts_defined(self):
        """测试转换提示词已定义"""
        from app.drawing.video_service import VideoService
        
        assert "negative_to_positive" in VideoService.TRANSFORMATION_PROMPTS
        assert "calm_meditation" in VideoService.TRANSFORMATION_PROMPTS
        assert "energetic_awakening" in VideoService.TRANSFORMATION_PROMPTS
    
    def test_negative_to_positive_prompt_content(self):
        """测试消极→积极转换提示词内容"""
        from app.drawing.video_service import VideoService
        
        prompt = VideoService.TRANSFORMATION_PROMPTS["negative_to_positive"]
        
        assert "阴暗" in prompt or "明亮" in prompt
        assert "雨" in prompt or "晴" in prompt or "阳光" in prompt
        assert "希望" in prompt or "温暖" in prompt


class TestCreateVideoFromImage:
    """测试从图片创建视频"""
    
    @pytest.mark.asyncio
    async def test_create_video_returns_task_id(self):
        """测试创建视频返回任务ID"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.create_video_task = AsyncMock(return_value="task_12345")
        
        service = VideoService(client=mock_client)
        
        result = await service.create_video_from_image(
            image_url="https://example.com/image.png",
            prompt="画面逐渐变亮"
        )
        
        assert result["task_id"] == "task_12345"
        assert result["status"] == "pending"
    
    @pytest.mark.asyncio
    async def test_create_video_with_custom_duration(self):
        """测试自定义时长"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.create_video_task = AsyncMock(return_value="task_67890")
        
        service = VideoService(client=mock_client)
        
        await service.create_video_from_image(
            image_url="https://example.com/image.png",
            prompt="测试",
            duration=10
        )
        
        mock_client.create_video_task.assert_called_once()
        call_kwargs = mock_client.create_video_task.call_args.kwargs
        assert call_kwargs["duration"] == 10


class TestCreateTransformationVideo:
    """测试情绪转换视频"""
    
    @pytest.mark.asyncio
    async def test_create_transformation_video_uses_correct_prompt(self):
        """测试使用正确的转换提示词"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.create_video_task = AsyncMock(return_value="task_transform")
        
        service = VideoService(client=mock_client)
        
        result = await service.create_transformation_video(
            image_url="https://example.com/image.png",
            transformation_type="negative_to_positive"
        )
        
        assert result["task_id"] == "task_transform"
        assert result["transformation_type"] == "negative_to_positive"
        
        call_kwargs = mock_client.create_video_task.call_args.kwargs
        assert "prompt" in call_kwargs
    
    @pytest.mark.asyncio
    async def test_create_transformation_video_fallback_prompt(self):
        """测试未知类型使用默认提示词"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.create_video_task = AsyncMock(return_value="task_fallback")
        
        service = VideoService(client=mock_client)
        
        result = await service.create_transformation_video(
            image_url="https://example.com/image.png",
            transformation_type="unknown_type"
        )
        
        assert result["task_id"] == "task_fallback"


class TestGetVideoStatus:
    """测试获取视频状态"""
    
    @pytest.mark.asyncio
    async def test_get_video_status_success(self):
        """测试获取视频状态"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.get_video_task_status = AsyncMock(return_value={
            "task_id": "task_123",
            "status": "succeeded",
            "video_url": "https://example.com/video.mp4"
        })
        
        service = VideoService(client=mock_client)
        
        result = await service.get_video_status("task_123")
        
        assert result["status"] == "succeeded"
        assert result["video_url"] == "https://example.com/video.mp4"
    
    @pytest.mark.asyncio
    async def test_get_video_status_processing(self):
        """测试视频处理中状态"""
        from app.drawing.video_service import VideoService
        
        mock_client = MagicMock()
        mock_client.get_video_task_status = AsyncMock(return_value={
            "task_id": "task_456",
            "status": "processing",
            "video_url": None
        })
        
        service = VideoService(client=mock_client)
        
        result = await service.get_video_status("task_456")
        
        assert result["status"] == "processing"
        assert result["video_url"] is None


class TestErrorHandling:
    """测试错误处理"""
    
    @pytest.mark.asyncio
    async def test_create_video_error_handling(self):
        """测试创建视频错误处理"""
        from app.drawing.video_service import VideoService, VideoServiceError
        from app.drawing.volcengine_client import VolcengineAPIError
        
        mock_client = MagicMock()
        mock_client.create_video_task = AsyncMock(
            side_effect=VolcengineAPIError("API错误")
        )
        
        service = VideoService(client=mock_client)
        
        with pytest.raises(VideoServiceError) as exc_info:
            await service.create_video_from_image(
                image_url="https://example.com/image.png",
                prompt="测试"
            )
        
        assert "创建视频任务失败" in str(exc_info.value)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
