"""
语音处理服务

提供语音转文字、口语化优化、LLM文本优化和情感分析功能

需求: 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 3.1
"""
import os
import re
from pathlib import Path
from typing import Optional
from openai import OpenAI
import logging
from datetime import datetime
import uuid

from .external_api_client import (
    get_external_api_client,
    ASRAPIError,
    LLMAPIError,
    VoiceEmotionAPIError
)

logger = logging.getLogger(__name__)


class VoiceProcessingService:
    """
    语音处理服务类
    
    提供语音转文字功能，支持常见音频格式（WAV、MP3、M4A）
    支持 save_mode 控制处理流程：
    - save_mode=1: 仅保存音频 + 语音情感分析
    - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项）
    - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析
    
    需求: 1.2, 1.3, 1.4
    """
    
    # 支持的音频格式
    SUPPORTED_FORMATS = {'.wav', '.mp3', '.m4a', '.ogg', '.flac', '.webm'}
    
    # 中文填充词列表 - 独立使用时删除
    # 需求: 2.2
    STANDALONE_FILLERS = [
        # 重复语气词（先匹配较长的）
        "嗯嗯嗯", "啊啊啊", "呃呃呃",
        "嗯嗯", "啊啊", "呃呃",
        # 单个语气词
        "嗯", "啊", "呃", "额", "哦", "哎", "诶", "嘿", "哈"
    ]
    
    # 短语填充词 - 后跟逗号/顿号时删除
    # 需求: 2.2
    PHRASE_FILLERS = [
        "那个", "就是", "然后", "这个"
    ]
    
    def __init__(self, api_key: Optional[str] = None):
        """
        初始化语音处理服务
        
        Args:
            api_key: OpenAI API密钥，如果不提供则从环境变量读取
        """
        # 从环境变量或参数获取API密钥
        self.api_key = api_key or os.getenv("OPENAI_API_KEY")
        
        if not self.api_key:
            logger.warning("OpenAI API key not provided. Voice transcription will not work.")
        
        # 初始化OpenAI客户端
        self.client = OpenAI(api_key=self.api_key) if self.api_key else None
        
        # 获取外部API客户端
        self.external_api_client = get_external_api_client()
    
    def _validate_audio_format(self, file_path: str) -> bool:
        """
        验证音频文件格式
        
        Args:
            file_path: 音频文件路径
            
        Returns:
            bool: 格式是否支持
        """
        file_extension = Path(file_path).suffix.lower()
        return file_extension in self.SUPPORTED_FORMATS

    async def process_with_options(
        self,
        audio_file_path: str,
        diary_id: int,
        media_id: Optional[int],
        save_mode: int,
        user_id: int,
        db
    ) -> dict:
        """
        根据 save_mode 处理语音
        
        处理流程：
        - save_mode=1: 仅保存音频 + 语音情感分析
        - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项，创建文本媒体项）
        - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析（创建文本媒体项）
        
        数据存储逻辑（需求 4.1, 4.2, 4.3, 4.4）：
        - 根据 save_mode 创建对应媒体项（DiaryMedia）
        - 保存转录结果到 voice_transcriptions 表
        - 同步情绪记录到 emotion_records 表
        
        Args:
            audio_file_path: 音频文件路径
            diary_id: 日记ID
            media_id: 媒体ID（save_mode=1或3时需要，用于关联音频媒体项）
            save_mode: 保存模式（1=仅语音, 2=仅文字, 3=两者）
            user_id: 用户ID
            db: 数据库会话
            
        Returns:
            dict: 处理结果
                - success: 是否成功
                - media_id: 音频媒体ID（save_mode=1或3时）
                - text_media_id: 文本媒体ID（save_mode=2或3时）
                - transcription_id: 转录记录ID
                - original_text: 原始转录文本
                - processed_text: 处理后的流畅文本
                - emotion: 情感分析结果
                - error: 错误信息（如果有）
            
        需求: 1.2, 1.3, 1.4, 4.1, 4.2, 4.3, 4.4
        """
        from ..models.database import VoiceTranscription, EmotionRecord, DiaryMedia
        from sqlalchemy import select, func
        
        # 生成请求ID和时间戳
        request_id = str(uuid.uuid4())
        timestamp = datetime.now()
        
        logger.warning(f"VOICE_SERVICE_FILE={__file__}")
        logger.warning("VOICE_PROCESS_WITH_OPTIONS_VERSION=2026-01-24-1613")
        logger.info(f"Starting voice processing with save_mode={save_mode}, diary_id={diary_id}, request_id={request_id}")
        
        # 验证 save_mode
        if save_mode not in [1, 2, 3]:
            return {
                "success": False,
                "error": f"无效的 save_mode: {save_mode}，有效值为 1, 2, 3"
            }
        
        # 验证文件存在
        if not os.path.exists(audio_file_path):
            return {
                "success": False,
                "error": f"音频文件不存在: {audio_file_path}"
            }
        
        # 验证音频格式
        if not self._validate_audio_format(audio_file_path):
            file_extension = Path(audio_file_path).suffix
            return {
                "success": False,
                "error": f"不支持的音频格式: {file_extension}，支持的格式: {', '.join(self.SUPPORTED_FORMATS)}"
            }
        
        result = {
            "success": True,
            "status": "success",  # success | processing | failure
            "task_id": None,  # ASR 任务ID（用于后续查询）
            "media_id": None,  # 将在后续设置为 diary_media 的 ID
            "media_url": None,  # ✅ 音频媒体URL
            "text_media_id": None,  # 新增：文本媒体项ID
            "transcription_id": None,
            "original_text": None,
            "processed_text": None,
            "emotion": None,
            "error": None,
            "message": None,  # 状态消息
            "asr_status": None  # 百度 ASR 返回的原始状态
        }
        
        try:
            # 步骤0: 如果需要保存音频（save_mode=1或3），创建音频媒体项
            audio_media_id = None
            if save_mode in [1, 3] and media_id is not None:
                logger.info("Step 0: Creating audio media item")
                
                # 查询 media_files 表获取音频资源信息（已在 OSS 中）
                from ..models.database import MediaFile
                stmt = select(MediaFile).where(MediaFile.id == media_id)
                file_result = await db.execute(stmt)
                media_file = file_result.scalar_one_or_none()
                
                if not media_file:
                    return {
                        "success": False,
                        "error": f"未找到ID为 {media_id} 的音频资源"
                    }
                
                # 查询当前日记的最大 sort_order
                stmt = (
                    select(func.coalesce(func.max(DiaryMedia.sort_order), -1))
                    .where(DiaryMedia.diary_id == diary_id)
                )
                max_order_result = await db.execute(stmt)
                max_order = max_order_result.scalar()
                new_sort_order = max_order + 1
                
                # 创建音频媒体项
                # 使用 media_files 表中的 URL（已在 OSS 中）
                audio_media = DiaryMedia(
                    diary_id=diary_id,
                    asset_id=media_id,  # 关联到 media_files 表
                    media_type="audio",
                    content=None,
                    media_url=media_file.url,  # 使用 OSS URL
                    thumbnail_url=None,
                    duration=media_file.duration_ms,
                    file_size=media_file.size_bytes,
                    sort_order=new_sort_order,
                    created_at=timestamp
                )
                db.add(audio_media)
                await db.flush()
                audio_media_id = audio_media.id
                result["media_id"] = audio_media_id
                result["media_url"] = media_file.url  # ✅ 添加音频URL到返回结果
                logger.info(f"Created audio media item with id={audio_media_id}, asset_id={media_id}")
            
            # 步骤1: 如果需要转文字（save_mode=2或3），先进行 ASR
            original_text = None
            processed_text = None
            oss_key = None  # 用于传递给 ASR
            asr_task_id = None  # ASR 任务ID
            asr_timeout = False  # 是否超时
            
            if save_mode in [2, 3]:
                # 1.1 获取音频的 OSS key（文件已在 OSS 中，由 /media/upload 上传）
                if media_id and db:
                    from ..models.database import MediaFile
                    from sqlalchemy import select as sa_select
                    
                    stmt = sa_select(MediaFile).where(MediaFile.id == media_id)
                    r = await db.execute(stmt)
                    media_file = r.scalar_one_or_none()
                    
                    if media_file and media_file.oss_key:
                        oss_key = media_file.oss_key
                        logger.info(f"Using existing OSS key from media_files: key={oss_key}")
                    else:
                        logger.warning(f"Media file {media_id} not found in OSS or missing oss_key")
                
                # 1.2 创建 ASR 任务并等待结果（使用新的指数退避轮询）
                logger.info("Step 1: Creating ASR task and waiting for result")
                
                # 获取 OSS URL
                if not oss_key:
                    raise ASRAPIError("OSS 未启用或上传失败，无法调用百度 ASR")
                
                from ..services.oss_service import OSSService
                from ..config import settings
                
                oss_service = OSSService(
                    endpoint=settings.oss_endpoint,
                    bucket_name=settings.oss_bucket,
                    access_key_id=settings.oss_access_key_id,
                    access_key_secret=settings.oss_access_key_secret,
                    public_base_url=settings.oss_public_base_url
                )
                
                audio_url = oss_service.generate_signed_url_for_baidu_asr(
                    key=oss_key,
                    expires_seconds=24 * 60 * 60,
                    content_type="audio/mp4",
                    use_public_url=None,
                )
                
                logger.info(f"Generated OSS URL for Baidu ASR: {audio_url}")
                
                # 创建 ASR 任务
                asr_task_id = await self.external_api_client.create_asr_task(
                    audio_file_path=audio_file_path,
                    oss_url=audio_url
                )
                result["task_id"] = asr_task_id
                logger.info(f"Created ASR task: {asr_task_id}")
                
                # 等待 ASR 结果（使用指数退避，超时不抛异常）
                text, asr_result = await self.external_api_client.wait_asr_result(
                    task_id=asr_task_id,
                    max_wait_seconds=90,  # 90秒超时
                    initial_interval=1.5,
                    max_interval=10.0
                )
                
                result["asr_status"] = asr_result.get("status")
                
                if text is None:
                    # ASR 超时，返回 processing 状态
                    asr_timeout = True
                    logger.warning(f"ASR task {asr_task_id} timeout, returning processing status")
                    result["status"] = "processing"
                    result["success"] = True  # 不算失败
                    result["message"] = "转写处理中，请稍后刷新获取结果"
                    
                    # 提交已创建的音频媒体项（如果有）
                    if audio_media_id:
                        await db.commit()
                    
                    return result
                
                # ASR 成功
                original_text = text
                result["original_text"] = original_text
                
                # 2.2 去除填充词
                logger.info("Step 2: Removing filler words")
                text_without_fillers = self.remove_filler_words(original_text)
                
                # 2.3 LLM 文本优化
                logger.info("Step 3: Optimizing text with LLM")
                processed_text = await self.optimize_text_with_llm(text_without_fillers)
                result["processed_text"] = processed_text
            
            # 步骤2: 情绪分析（所有模式都执行）
            # 如果有转录文本，使用文本情绪分析；否则返回中性结果
            logger.info("Step 4: Analyzing emotion")
            if processed_text:
                # 使用转录后的文本进行情绪分析（百度 NLP 对话情绪识别）
                emotion_result = await self.analyze_voice_emotion(processed_text, is_text=True)
            else:
                # save_mode=1 时没有转录文本，返回中性结果
                emotion_result = {
                    "emotion_type": "中性",
                    "score": 50,
                    "confidence": 0.0
                }
            result["emotion"] = emotion_result
                
            # 步骤3: 如果需要转文字，创建文本媒体项和保存转录结果
            if save_mode in [2, 3]:
                # 3.1 创建文本媒体项（需求 4.2 - 根据 save_mode 创建对应媒体项）
                logger.info("Step 5: Creating text media item")
                
                # 查询当前日记的最大 sort_order
                stmt = (
                    select(func.coalesce(func.max(DiaryMedia.sort_order), -1))
                    .where(DiaryMedia.diary_id == diary_id)
                )
                max_order_result = await db.execute(stmt)
                max_order = max_order_result.scalar()
                new_sort_order = max_order + 1
                
                # 创建文本媒体项（存储流畅文本）
                text_media = DiaryMedia(
                    diary_id=diary_id,
                    asset_id=None,  # 文本类型不需要关联上传资源
                    media_type="text",
                    content=processed_text,  # 存储处理后的流畅文本
                    media_url=None,
                    thumbnail_url=None,
                    duration=None,
                    file_size=len(processed_text.encode('utf-8')) if processed_text else 0,
                    sort_order=new_sort_order,
                    created_at=timestamp
                )
                db.add(text_media)
                await db.flush()
                result["text_media_id"] = text_media.id
                logger.info(f"Created text media item with id={text_media.id}")
                
                # 3.2 存储转录结果到 voice_transcriptions 表（需求 4.3）
                logger.info("Step 6: Storing transcription to database")
                transcription = VoiceTranscription(
                    diary_id=diary_id,
                    media_id=audio_media_id if save_mode == 3 else None,  # 使用 diary_media 的 ID
                    original_text=original_text,
                    processed_text=processed_text,
                    confidence=emotion_result.get("confidence", 0.0) if emotion_result else 0.0,
                    detected_emotion=emotion_result.get("emotion_type") if emotion_result else None,
                    emotion_score=emotion_result.get("score") if emotion_result else None,
                    created_at=timestamp
                )
                db.add(transcription)
                await db.flush()
                result["transcription_id"] = transcription.id
                logger.info(f"Created voice transcription with id={transcription.id}")
            
            # 步骤4: 同步情绪记录到 emotion_records 表（需求 4.4）
            if emotion_result:
                logger.info("Step 7: Syncing emotion record to emotion_records table")
                
                # 确定 source_id：优先使用 transcription_id，否则使用 audio_media_id
                source_id = result.get("transcription_id") or audio_media_id
                
                emotion_record = EmotionRecord(
                    user_id=user_id,
                    source_type="voice",
                    source_id=source_id,
                    emotion_type=emotion_result.get("emotion_type", "中性"),
                    emotion_score=emotion_result.get("score", 50),
                    analysis=f"语音情感分析结果，置信度: {emotion_result.get('confidence', 0):.2f}",
                    recorded_at=timestamp,
                    created_at=timestamp
                )
                db.add(emotion_record)
                logger.info(f"Created emotion record for source_type='voice', source_id={source_id}")
            
            await db.commit()
            result["status"] = "success"
            logger.info(f"Voice processing completed successfully. request_id={request_id}")
            
        except ASRAPIError as e:
            logger.error(f"ASR API error: {str(e)}")
            # ASR 失败时的降级处理
            # 如果是 save_mode=1（仅保存音频），提交已创建的音频媒体项
            # 如果是 save_mode=2或3，回滚并返回错误
            if save_mode == 1:
                # 仅保存音频模式，ASR 失败不影响音频保存
                logger.warning("ASR failed in save_mode=1, but audio media item will be saved")
                try:
                    await db.commit()
                    result["success"] = True
                    result["status"] = "success"
                    result["error"] = f"音频已保存，但语音识别失败: {str(e)}"
                except Exception as commit_error:
                    logger.error(f"Failed to commit audio media item: {commit_error}")
                    await db.rollback()
                    result["success"] = False
                    result["status"] = "failure"
                    result["error"] = f"保存失败: {str(commit_error)}"
            else:
                # save_mode=2或3，需要转文字，ASR 明确失败则返回 failure
                await db.rollback()
                result["success"] = False
                result["status"] = "failure"
                result["error"] = f"语音识别失败: {str(e)}"
        except LLMAPIError as e:
            logger.error(f"LLM API error: {str(e)}")
            await db.rollback()
            result["success"] = False
            result["status"] = "failure"
            result["error"] = f"文本优化失败: {str(e)}"
        except VoiceEmotionAPIError as e:
            logger.error(f"Voice emotion API error: {str(e)}")
            # 情感分析失败时降级处理，继续保存其他结果
            logger.warning("Emotion analysis failed, using fallback")
            result["emotion"] = {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0
            }
        except Exception as e:
            logger.error(f"Voice processing failed: {str(e)}")
            await db.rollback()
            result["success"] = False
            result["status"] = "failure"
            result["error"] = f"语音处理失败: {str(e)}"
        
        return result

    async def transcribe_audio(self, audio_file_path: str, media_id: Optional[int] = None, oss_key: Optional[str] = None, db = None) -> str:
        """
        语音转文字
        
        优先使用外部 ASR API，如果未配置则使用 OpenAI Whisper API
        
        Args:
            audio_file_path: 音频文件路径（用于格式检测）
            media_id: 媒体ID（用于日志记录）
            oss_key: OSS 对象键（如果已上传，必须提供）
            db: 数据库会话
            
        Returns:
            str: 识别出的文字内容
            
        Raises:
            ASRAPIError: 如果转录失败
            
        需求: 2.1
        """
        # 验证文件是否存在
        if not os.path.exists(audio_file_path):
            raise ASRAPIError(f"音频文件不存在: {audio_file_path}")
        
        # 验证音频格式
        if not self._validate_audio_format(audio_file_path):
            file_extension = Path(audio_file_path).suffix
            raise ASRAPIError(
                f"不支持的音频格式: {file_extension}，"
                f"支持的格式: {', '.join(self.SUPPORTED_FORMATS)}"
            )
        
        # 优先尝试使用外部 ASR API
        if self.external_api_client.is_asr_configured():
            try:
                logger.info("Using external ASR API")
                
                # 必须提供 oss_key 才能调用百度 ASR
                if not oss_key:
                    raise ASRAPIError("OSS 未启用或上传失败，且 server URL 不可公网访问，无法调用百度 ASR")
                
                from ..services.oss_service import OSSService
                from ..config import settings
                
                oss_service = OSSService(
                    endpoint=settings.oss_endpoint,
                    bucket_name=settings.oss_bucket,
                    access_key_id=settings.oss_access_key_id,
                    access_key_secret=settings.oss_access_key_secret,
                    public_base_url=settings.oss_public_base_url
                )
                
                # 自动检测 bucket ACL，优先使用公共 URL（如果 bucket 是 public-read）
                # generate_signed_url_for_baidu_asr 会自动检测并选择最佳方式
                audio_url = oss_service.generate_signed_url_for_baidu_asr(
                    key=oss_key,
                    expires_seconds=24 * 60 * 60,  # 24h
                    content_type="audio/mp4",
                    use_public_url=None,  # None 表示自动检测
                )
                
                logger.info(f"Generated OSS URL for Baidu ASR: {audio_url}")
                
                # ✅ 使用 OSS URL 调用百度 ASR
                # audio_file_path 用于推断音频格式，oss_url 传 OSS URL 让百度拉取
                try:
                    return await self.external_api_client.speech_to_text(
                        audio_file_path=audio_file_path,
                        oss_url=audio_url
                    )
                except ASRAPIError as e:
                    # 如果失败（错误码 6: No permission to access data），尝试不同的 URL 生成方式
                    error_msg = str(e)
                    if "[6]" in error_msg or "No permission" in error_msg or "权限" in error_msg:
                        logger.warning(f"OSS URL failed with permission error, trying alternative URL format: {error_msg}")
                        
                        # 尝试1：如果之前用的是公共URL，改用签名URL
                        if oss_service.is_bucket_public_read():
                            logger.info("Retrying with signed URL (bucket is public-read but public URL failed)")
                            signed_url = oss_service.generate_signed_url_for_baidu_asr(
                                key=oss_key,
                                expires_seconds=24 * 60 * 60,
                                content_type="audio/mp4",
                                use_public_url=False,  # 强制使用签名 URL
                            )
                            logger.info(f"Retrying with signed OSS URL: {signed_url}")
                            return await self.external_api_client.speech_to_text(
                                audio_file_path=audio_file_path,
                                oss_url=signed_url
                            )
                        else:
                            # 如果之前用的是签名URL，可能是百度ASR的App权限问题
                            logger.error(f"Both public and signed URLs failed. This might be a Baidu ASR App permission issue.")
                            logger.error(f"Please check: 1) Baidu ASR App has speech recognition permission enabled")
                            logger.error(f"2) OSS bucket ACL and URL accessibility")
                            raise ASRAPIError(
                                f"OSS URL access failed: {error_msg}. "
                                f"This might be a Baidu ASR App permission issue. "
                                f"Please check Baidu Cloud Console -> App Management -> Speech Recognition permission."
                            )
                    else:
                        # 其他错误，直接抛出
                        raise
            except ASRAPIError:
                raise
            except Exception as e:
                raise ASRAPIError(f"外部 ASR API 调用失败: {str(e)}")
        
        # 降级使用 OpenAI Whisper API
        if not self.client:
            raise ASRAPIError("ASR API 未配置，且 OpenAI API 密钥未提供")
        
        try:
            logger.info("Using OpenAI Whisper API for transcription")
            with open(audio_file_path, "rb") as audio_file:
                response = self.client.audio.transcriptions.create(
                    model="whisper-1",
                    file=audio_file,
                    response_format="text"
                )
                
                transcribed_text = response if isinstance(response, str) else response.text
                
                if not transcribed_text or not transcribed_text.strip():
                    logger.warning("Transcription returned empty text")
                    return ""
                
                return transcribed_text.strip()
                
        except Exception as e:
            logger.error(f"Transcription failed: {str(e)}")
            raise ASRAPIError(f"语音转文字失败: {str(e)}")

    def remove_filler_words(self, text: str) -> str:
        """
        去除中文填充词
        
        去除常见的填充词，包括：
        - 语气词：嗯、啊、呃、额、哦、哎、诶、嘿、哈
        - 停顿词：那个、就是、然后、这个（后跟逗号/顿号时删除）
        - 重复语气：嗯嗯、啊啊、呃呃、嗯嗯嗯、啊啊啊、呃呃呃
        
        Args:
            text: 原始文本
            
        Returns:
            str: 处理后的文本（长度 ≤ 原始文本长度）
            
        需求: 2.2
        """
        if not text:
            return text
        
        processed_text = text
        
        # 处理独立填充词（后面通常跟标点或空格）
        # 按长度从长到短排序，确保先匹配较长的
        for filler in self.STANDALONE_FILLERS:
            # 匹配填充词及其后面的标点和空格
            # 支持的标点：中文逗号、顿号、句号、感叹号、问号、分号、冒号
            pattern = re.escape(filler) + r'[，、。！？；：\s]*'
            processed_text = re.sub(pattern, '', processed_text)
        
        # 处理短语填充词（只在后面跟逗号/顿号时删除）
        for filler in self.PHRASE_FILLERS:
            # 只删除后面跟逗号或顿号的情况
            pattern = re.escape(filler) + r'[，、\s]+'
            processed_text = re.sub(pattern, '', processed_text)
        
        # 清理多余的空格
        processed_text = re.sub(r'\s+', ' ', processed_text)
        
        # 去除句首句尾的空格
        processed_text = processed_text.strip()
        
        # 清理句首的标点符号
        processed_text = re.sub(r'^[，、；：\s]+', '', processed_text)
        
        # 清理连续的标点符号（如 "，，" -> "，"）
        processed_text = re.sub(r'([，。！？、；：])\1+', r'\1', processed_text)
        
        # 去除标点前的空格
        processed_text = re.sub(r'\s+([，。！？、；：])', r'\1', processed_text)
        
        return processed_text

    async def optimize_text_with_llm(self, text: str) -> str:
        """
        使用 LLM API 优化文本，使其更自然流畅
        
        优先使用外部 LLM API，如果未配置则使用 OpenAI API
        
        Args:
            text: 去除填充词后的文本
            
        Returns:
            str: 优化后的流畅文本
            
        Raises:
            LLMAPIError: 如果优化失败
            
        需求: 2.3
        """
        if not text or not text.strip():
            return text
        
        # 优先尝试使用外部 LLM API
        if self.external_api_client.is_llm_configured():
            try:
                logger.info("Using external LLM API for text optimization")
                return await self.external_api_client.optimize_text(text)
            except LLMAPIError:
                raise
            except Exception as e:
                raise LLMAPIError(f"外部 LLM API 调用失败: {str(e)}")
        
        # 降级使用 OpenAI API
        if not self.client:
            # 如果没有配置任何 LLM API，返回原文本
            logger.warning("LLM API not configured, returning original text")
            return text
        
        try:
            logger.info("Using OpenAI API for text optimization")
            
            # 构建优化提示词
            prompt = f"""请优化以下中文文本，使其更加自然流畅，保持原意不变。
只返回优化后的文本，不要添加任何解释或说明。

原文：
{text}

优化后的文本："""
            
            response = self.client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=[
                    {
                        "role": "system",
                        "content": "你是一个专业的中文文本编辑，擅长将口语化的文本优化为流畅的书面语，同时保持原意不变。"
                    },
                    {
                        "role": "user",
                        "content": prompt
                    }
                ],
                max_tokens=1000,
                temperature=0.3
            )
            
            optimized_text = response.choices[0].message.content.strip()
            
            if not optimized_text:
                logger.warning("LLM returned empty text, using original")
                return text
            
            logger.info(f"Text optimization completed. Original: {len(text)} chars, Optimized: {len(optimized_text)} chars")
            return optimized_text
            
        except Exception as e:
            logger.error(f"Text optimization failed: {str(e)}")
            raise LLMAPIError(f"文本优化失败: {str(e)}")

    async def analyze_voice_emotion(self, audio_path_or_text: str, is_text: bool = False) -> dict:
        """
        分析情感
        
        支持两种模式：
        1. 基于文本的情绪分析（推荐）：使用百度 NLP 对话情绪识别 API
        2. 降级方案：返回中性结果
        
        Args:
            audio_path_or_text: 音频文件路径或转录后的文本
            is_text: 如果为 True，则 audio_path_or_text 是文本；否则是音频路径
            
        Returns:
            dict: 情感分析结果
                - emotion_type: 情绪类型（积极/消极/中性）
                - score: 情绪评分（1-100，50为中性）
                - confidence: 置信度（0-1）
            
        Raises:
            VoiceEmotionAPIError: 如果分析失败
            
        需求: 3.1
        """
        # 如果是文本模式，直接使用文本情绪分析
        if is_text:
            text = audio_path_or_text
            if not text or not text.strip():
                return {
                    "emotion_type": "中性",
                    "score": 50,
                    "confidence": 0.0
                }
            
            # 使用百度 NLP 对话情绪识别 API
            if self.external_api_client.is_voice_emotion_configured():
                try:
                    logger.info("Using Baidu NLP emotion recognition API for text")
                    result = await self.external_api_client.analyze_text_emotion(text)
                    
                    # 验证结果格式
                    emotion_type = result.get("emotion_type", "中性")
                    score = result.get("score", 50)
                    confidence = result.get("confidence", 0.0)
                    
                    # 确保评分在有效范围内
                    score = max(1, min(100, int(score)))
                    confidence = max(0.0, min(1.0, float(confidence)))
                    
                    return {
                        "emotion_type": emotion_type,
                        "score": score,
                        "confidence": confidence
                    }
                except VoiceEmotionAPIError:
                    raise
                except Exception as e:
                    raise VoiceEmotionAPIError(f"文本情绪分析 API 调用失败: {str(e)}")
            
            # 降级方案：返回中性结果
            logger.warning("Text emotion API not configured, using fallback neutral result")
            return {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0
            }
        
        # 音频路径模式（保留向后兼容）
        audio_path = audio_path_or_text
        
        # 验证文件存在
        if not os.path.exists(audio_path):
            raise VoiceEmotionAPIError(f"音频文件不存在: {audio_path}")
        
        # 由于百度没有直接的音频情感分析 API，返回降级结果
        # 实际的情感分析应该在转录文本后调用 analyze_voice_emotion(text, is_text=True)
        logger.warning("Direct audio emotion analysis not supported, returning neutral result")
        return {
            "emotion_type": "中性",
            "score": 50,
            "confidence": 0.0
        }

    async def analyze_emotion(self, text: str) -> dict:
        """
        基于文本的情感分析（保留用于向后兼容）
        
        使用transformers库加载中文情感分析模型，分析文本的情感倾向
        支持中文和英文文本
        
        Args:
            text: 待分析的文本
            
        Returns:
            dict: 情感分析结果，包含：
                - emotion_type: 情感类型（积极/消极/中性）
                - score: 情感强度评分（1-100）
                - confidence: 置信度（0-1）
                - details: 详细信息（可选）
        """
        if not text or not text.strip():
            return {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0,
                "details": {"reason": "empty_text"}
            }
        
        try:
            from transformers import pipeline
            
            # 检测文本语言（简单判断：是否包含中文字符）
            is_chinese = any('\u4e00' <= char <= '\u9fff' for char in text)
            
            # 根据语言选择模型
            if is_chinese:
                model_name = "uer/roberta-base-finetuned-chinanews-chinese"
            else:
                model_name = "distilbert-base-uncased-finetuned-sst-2-english"
            
            # 创建情感分析pipeline（使用缓存避免重复加载模型）
            if not hasattr(self, '_sentiment_analyzer') or \
               not hasattr(self, '_current_model') or \
               self._current_model != model_name:
                logger.info(f"Loading sentiment analysis model: {model_name}")
                self._sentiment_analyzer = pipeline(
                    "sentiment-analysis",
                    model=model_name,
                    framework="pt",
                    device=-1
                )
                self._current_model = model_name
            
            # 执行情感分析
            result = self._sentiment_analyzer(text[:512])[0]
            
            # 解析结果
            label = result['label'].upper()
            confidence = float(result['score'])
            
            # 映射标签到情感类型
            emotion_mapping = {
                'POSITIVE': '积极',
                'NEGATIVE': '消极',
                'NEUTRAL': '中性',
                'LABEL_1': '积极',
                'LABEL_0': '消极',
            }
            
            emotion_type = emotion_mapping.get(label, '中性')
            
            # 计算情感评分（1-100）
            if emotion_type == '积极':
                score = int(50 + confidence * 50)
            elif emotion_type == '消极':
                score = int(50 - confidence * 49)
            else:
                score = 50
            
            score = max(1, min(100, score))
            
            logger.info(f"Text emotion analysis completed: {emotion_type}, score: {score}")
            
            return {
                "emotion_type": emotion_type,
                "score": score,
                "confidence": confidence,
                "details": {
                    "original_label": label,
                    "model": model_name,
                    "language": "chinese" if is_chinese else "english"
                }
            }
            
        except Exception as e:
            logger.error(f"Text emotion analysis failed: {str(e)}")
            return {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0,
                "details": {
                    "error": str(e),
                    "fallback": True
                }
            }

    async def process_voice_complete(
        self,
        audio_file_path: str,
        diary_id: int,
        media_id: int,
        db
    ) -> dict:
        """
        完整的语音处理流程（保留用于向后兼容）
        
        依次执行：语音转文字 -> 去除冗余词 -> 情感分析 -> 存储到数据库
        
        Args:
            audio_file_path: 音频文件路径
            diary_id: 日记ID
            media_id: 媒体ID
            db: 数据库会话
            
        Returns:
            dict: 语音处理结果
        """
        from ..models.database import VoiceTranscription
        
        request_id = str(uuid.uuid4())
        timestamp = datetime.now()
        
        logger.info(f"Starting complete voice processing for diary_id={diary_id}, media_id={media_id}")
        
        try:
            # 步骤1: 语音转文字
            original_text = await self.transcribe_audio(audio_file_path)
            
            # 步骤2: 去除冗余词
            processed_text = self.remove_filler_words(original_text)
            
            # 步骤3: 基于文本的情感分析
            emotion_result = await self.analyze_emotion(processed_text)
            
            # 步骤4: 存储到数据库
            transcription = VoiceTranscription(
                diary_id=diary_id,
                media_id=media_id,
                original_text=original_text,
                processed_text=processed_text,
                confidence=emotion_result['confidence'],
                detected_emotion=emotion_result['emotion_type'],
                emotion_score=emotion_result['score'],
                created_at=timestamp
            )
            
            db.add(transcription)
            await db.commit()
            await db.refresh(transcription)
            
            logger.info(f"Voice processing completed. Transcription ID: {transcription.id}")
            
            return {
                "original_text": original_text,
                "processed_text": processed_text,
                "emotion": {
                    "emotion_type": emotion_result['emotion_type'],
                    "score": emotion_result['score'],
                    "confidence": emotion_result['confidence'],
                    "details": emotion_result.get('details')
                },
                "request_id": request_id,
                "timestamp": timestamp
            }
            
        except Exception as e:
            logger.error(f"Voice processing failed: {str(e)}")
            await db.rollback()
            raise RuntimeError(f"Voice processing failed: {str(e)}") from e
