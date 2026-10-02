"""
视频生成业务服务

提供画作转视频相关的业务逻辑：
- 将画作转换为动画视频
- 生成消极→积极情绪转换视频
"""
import logging
from typing import Optional, Dict, Any

from .volcengine_client import VolcengineClient, get_volcengine_client, VolcengineAPIError

logger = logging.getLogger(__name__)


class VideoServiceError(Exception):
    """视频服务异常"""
    pass


class VideoService:
    """
    视频生成服务
    
    功能：
    1. 图片转动画视频
    2. 情绪转换视频（消极→积极）
    """
    
    # 情绪转换提示词模板
    TRANSFORMATION_PROMPTS = {
        "negative_to_positive": (
            "画面逐渐从阴暗转向明亮，"
            "如果有下雨的场景则雨过天晴、太阳出来、彩虹出现；"
            "如果有枯萎的植物则开始生长、开花；"
            "如果有孤独的场景则有温暖的陪伴出现；"
            "整体氛围从压抑转为充满希望和温暖，"
            "色彩从冷色调渐变为暖色调，"
            "带来治愈和安慰的感觉"
        ),
        "calm_meditation": (
            "画面缓缓流动，如同冥想般平静，"
            "轻柔的光影变化，舒缓的色彩过渡，"
            "营造宁静祥和的氛围"
        ),
        "energetic_awakening": (
            "画面充满活力，色彩鲜明跳跃，"
            "动感的光线效果，充满能量和激情，"
            "像清晨第一缕阳光唤醒世界"
        )
    }
    
    def __init__(self, client: Optional[VolcengineClient] = None):
        """
        初始化视频服务
        
        Args:
            client: 火山引擎客户端
        """
        self.client = client or get_volcengine_client()
    
    async def create_video_from_image(
        self,
        image_url: str,
        prompt: str,
        duration: int = 5,
        camera_fixed: bool = False,
        watermark: bool = True
    ) -> Dict[str, Any]:
        """
        从图片生成动画视频
        
        Args:
            image_url: 首帧图片URL
            prompt: 视频动画描述
            duration: 视频时长（3-10秒）
            camera_fixed: 是否固定摄像机
            watermark: 是否添加水印
            
        Returns:
            dict: {task_id, status}
        """
        logger.info(f"创建视频: image_url={image_url}, prompt={prompt[:50]}...")
        
        try:
            task_id = await self.client.create_video_task(
                image_url=image_url,
                prompt=prompt,
                duration=duration,
                camera_fixed=camera_fixed,
                watermark=watermark
            )
            
            return {
                "task_id": task_id,
                "status": "pending"
            }
            
        except VolcengineAPIError as e:
            logger.error(f"创建视频任务失败: {e}")
            raise VideoServiceError(f"创建视频任务失败: {str(e)}")
    
    async def create_transformation_video(
        self,
        image_url: str,
        transformation_type: str = "negative_to_positive",
        duration: int = 5
    ) -> Dict[str, Any]:
        """
        创建情绪转换视频
        
        将画作转换为消极→积极的动画视频
        
        Args:
            image_url: 原始画作URL
            transformation_type: 转换类型
            duration: 视频时长
            
        Returns:
            dict: {task_id, status, transformation_type}
        """
        logger.info(f"创建转换视频: type={transformation_type}, image={image_url}")
        
        # 获取转换提示词
        prompt = self.TRANSFORMATION_PROMPTS.get(
            transformation_type,
            self.TRANSFORMATION_PROMPTS["negative_to_positive"]
        )
        
        try:
            task_id = await self.client.create_video_task(
                image_url=image_url,
                prompt=prompt,
                duration=duration,
                camera_fixed=False,
                watermark=True
            )
            
            return {
                "task_id": task_id,
                "status": "pending",
                "transformation_type": transformation_type
            }
            
        except VolcengineAPIError as e:
            logger.error(f"创建转换视频失败: {e}")
            raise VideoServiceError(f"创建转换视频失败: {str(e)}")
    
    async def get_video_status(self, task_id: str) -> Dict[str, Any]:
        """
        获取视频生成任务状态

        任务成功后会对无声视频做一次后期配乐（ffmpeg 混入疗愈音乐并上传 OSS），
        配乐失败时自动降级返回原始无声视频 URL。

        Args:
            task_id: 任务ID

        Returns:
            dict: {task_id, status, video_url?, error?}
        """
        try:
            result = await self.client.get_video_task_status(task_id)
            # 视频渲染成功：异步配乐，替换为带音轨的 URL
            if result.get("status") == "succeeded" and result.get("video_url"):
                from .video_audio_mixer import add_background_music
                result["video_url"] = await add_background_music(
                    task_id, result["video_url"]
                )
            return result
        except VolcengineAPIError as e:
            logger.error(f"获取视频状态失败: {e}")
            raise VideoServiceError(f"获取视频状态失败: {str(e)}")
    
    async def wait_for_video(
        self,
        task_id: str,
        max_wait_seconds: int = 300
    ) -> str:
        """
        等待视频生成完成
        
        Args:
            task_id: 任务ID
            max_wait_seconds: 最大等待时间
            
        Returns:
            str: 视频URL
        """
        try:
            return await self.client.wait_for_video(
                task_id=task_id,
                max_wait_seconds=max_wait_seconds
            )
        except VolcengineAPIError as e:
            logger.error(f"等待视频生成失败: {e}")
            raise VideoServiceError(f"等待视频生成失败: {str(e)}")
