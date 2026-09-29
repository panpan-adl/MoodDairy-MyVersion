"""
语音处理路由

提供语音转文字、口语化优化和情感分析的API端点
验证需求: 8.1, 10.1, 11.1
"""
from fastapi import APIRouter, Depends, HTTPException, status, UploadFile, File, Form
from sqlalchemy.ext.asyncio import AsyncSession
import os
import uuid
from datetime import datetime

from app.database import get_db
from app.services.voice_service import VoiceProcessingService
from app.services.media_service import MediaService
from app.models.schemas import (
    VoiceProcessingResult,
    VoiceProcessWithOptionsResponse,
    AsrStatusResponse,
    EmotionResult,
    ErrorResponse
)
from app.services.external_api_client import get_external_api_client, ASRAPIError
from app.security.deps import AuthenticatedUserId, ensure_user_match
from app.utils.error_handler import (
    NotFoundError,
    FileError,
    VoiceProcessingError,
    validate_audio_format,
    validate_file_size
)

router = APIRouter(prefix="/voice", tags=["voice"])


async def _verify_diary_owner(db: AsyncSession, diary_id: int, user_id: int):
    from sqlalchemy import select
    from app.models.database import Diary

    stmt = select(Diary).where(Diary.id == diary_id)
    result = await db.execute(stmt)
    diary = result.scalar_one_or_none()
    if not diary:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"未找到ID为 {diary_id} 的日记",
        )
    ensure_user_match(user_id, diary.user_id)
    return diary


@router.post(
    "/process",
    response_model=VoiceProcessingResult,
    status_code=status.HTTP_200_OK,
    summary="统一语音处理API",
    description="执行完整的语音处理流程：语音转文字 -> 去除冗余词 -> 情感分析",
    responses={
        200: {"description": "语音处理成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误或音频格式不支持"},
        404: {"model": ErrorResponse, "description": "日记或媒体不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def process_voice(
    current_user_id: AuthenticatedUserId,
    audio: UploadFile = File(..., description="音频文件"),
    diary_id: int = Form(..., description="日记ID"),
    media_id: int = Form(..., description="媒体ID"),
    db: AsyncSession = Depends(get_db)
):
    """
    统一的语音处理API
    
    依次执行：
    1. 语音转文字
    2. 去除冗余词
    3. 情感分析
    4. 存储到数据库
    
    验证需求: 11.1, 11.2, 11.3
    
    Args:
        audio: 上传的音频文件
        diary_id: 关联的日记ID
        media_id: 关联的媒体ID
        db: 数据库会话
        
    Returns:
        VoiceProcessingResult: 包含原始文本、处理后文本、情感分析结果等
    """
    temp_file_path = None
    
    try:
        # 验证文件是否存在
        if not audio.filename:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="未提供音频文件"
            )
        
        # 验证音频格式
        file_extension = os.path.splitext(audio.filename)[1].lower()
        supported_formats = ['.wav', '.mp3', '.m4a', '.ogg', '.flac', '.webm']
        
        if file_extension not in supported_formats:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"不支持的音频格式: {file_extension}。支持的格式: {', '.join(supported_formats)}"
            )
        
        # 验证日记归属
        from sqlalchemy import select
        from app.models.database import DiaryMedia

        await _verify_diary_owner(db, diary_id, current_user_id)
        
        # 验证媒体是否存在
        stmt = select(DiaryMedia).where(DiaryMedia.id == media_id)
        result = await db.execute(stmt)
        media = result.scalar_one_or_none()
        
        if not media:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"未找到ID为 {media_id} 的媒体"
            )
        
        # 保存临时音频文件
        media_service = MediaService()
        temp_file_relative_path = await media_service.save_file(audio, "audio")
        
        # 转换为绝对路径
        temp_file_path = str(media_service.upload_dir / temp_file_relative_path)
        
        # 执行完整的语音处理流程
        voice_service = VoiceProcessingService()
        result = await voice_service.process_voice_complete(
            audio_file_path=temp_file_path,
            diary_id=diary_id,
            media_id=media_id,
            db=db
        )
        
        # 返回处理结果
        return VoiceProcessingResult(
            original_text=result["original_text"],
            processed_text=result["processed_text"],
            emotion=EmotionResult(**result["emotion"]),
            request_id=result["request_id"],
            timestamp=result["timestamp"]
        )
    
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"语音处理失败: {str(e)}"
        )
    finally:
        # 清理临时文件
        if temp_file_path and os.path.exists(temp_file_path):
            try:
                os.remove(temp_file_path)
            except Exception as e:
                print(f"清理临时文件失败: {e}")


@router.post(
    "/process-with-options",
    response_model=VoiceProcessWithOptionsResponse,
    status_code=status.HTTP_200_OK,
    summary="带选项的语音处理API",
    description="""
    根据 save_mode 执行不同的语音处理流程：
    - save_mode=1: 仅保存音频 + 语音情感分析
    - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项）
    - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析
    
    处理流程：音频文件 → ASR语音识别 → 原始文本 → 去除填充词 → 中间文本 → LLM优化 → 流畅文本
    """,
    responses={
        200: {"description": "语音处理成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误或音频格式不支持"},
        404: {"model": ErrorResponse, "description": "日记不存在"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def process_voice_with_options(
    current_user_id: AuthenticatedUserId,
    audio: UploadFile = File(..., description="音频文件"),
    diary_id: int = Form(..., description="日记ID"),
    user_id: int = Form(..., description="用户ID"),
    save_mode: int = Form(..., ge=1, le=3, description="保存模式(1=仅语音, 2=仅文字, 3=两者都保存)"),
    media_id: int = Form(None, description="媒体ID（save_mode=1或3时需要）"),
    db: AsyncSession = Depends(get_db)
):
    """
    带选项的语音处理API
    
    根据 save_mode 参数控制处理流程：
    - save_mode=1: 仅保存音频，执行语音情感分析
    - save_mode=2: 执行完整的语音转文字流程（ASR → 去填充词 → LLM优化），不保存音频媒体项
    - save_mode=3: 保存音频并执行完整的语音转文字流程
    
    验证需求: 1.2, 1.3, 1.4, 2.1, 3.1
    
    Args:
        audio: 上传的音频文件
        diary_id: 关联的日记ID
        user_id: 用户ID
        save_mode: 保存模式（1=仅语音, 2=仅文字, 3=两者都保存）
        media_id: 媒体ID（save_mode=1或3时需要）
        db: 数据库会话
        
    Returns:
        VoiceProcessWithOptionsResponse: 包含处理结果（原文、流畅文本、情绪）
    """
    temp_file_path = None
    
    try:
        # 验证文件是否存在
        if not audio.filename:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="未提供音频文件"
            )
        
        # 验证音频格式
        file_extension = os.path.splitext(audio.filename)[1].lower()
        supported_formats = ['.wav', '.mp3', '.m4a', '.ogg', '.flac', '.webm']
        
        if file_extension not in supported_formats:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"不支持的音频格式: {file_extension}。支持的格式: {', '.join(supported_formats)}"
            )
        
        # 验证 save_mode
        if save_mode not in [1, 2, 3]:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"无效的 save_mode: {save_mode}，有效值为 1, 2, 3"
            )
        
        # 验证 media_id（save_mode=1或3时需要）
        # 注意：这里的 media_id 是 media_uploads 表的 asset_id，不是 diary_media 表的 id
        # 在 save_mode=1或3 时，我们会在后续创建 diary_media 记录
        if save_mode in [1, 3] and media_id is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"save_mode={save_mode} 时需要提供 media_id（音频资源ID）"
            )
        
        # 验证用户与日记归属
        ensure_user_match(current_user_id, user_id)
        await _verify_diary_owner(db, diary_id, current_user_id)

        from sqlalchemy import select
        from app.models.database import MediaFile
        
        # 验证音频资源是否存在（如果提供了 media_id）
        # 这里的 media_id 是 media_files 表的 ID（音频已在 OSS 中）
        media_file = None
        if media_id is not None:
            stmt = select(MediaFile).where(MediaFile.id == media_id)
            result = await db.execute(stmt)
            media_file = result.scalar_one_or_none()
            
            if not media_file:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"未找到ID为 {media_id} 的音频资源"
                )
        
        # 确定音频文件路径
        # 如果 save_mode=1或3（需要保存音频），使用 OSS 中已上传的文件
        # 如果 save_mode=2（仅转文字），保存新的临时文件
        if save_mode in [1, 3] and media_file:
            # 音频已在 OSS 中，使用 OSS URL（不需要本地文件）
            # 但 voice_service 需要本地路径用于格式检测，所以仍需保存临时文件
            media_service = MediaService()
            temp_file_relative_path = await media_service.save_file(audio, "audio")
            temp_file_path = str(media_service.upload_dir / temp_file_relative_path)
        else:
            # 保存临时音频文件（用于 save_mode=2）
            media_service = MediaService()
            temp_file_relative_path = await media_service.save_file(audio, "audio")
            temp_file_path = str(media_service.upload_dir / temp_file_relative_path)
        
        # 执行语音处理
        voice_service = VoiceProcessingService()
        result = await voice_service.process_with_options(
            audio_file_path=temp_file_path,
            diary_id=diary_id,
            media_id=media_id,
            save_mode=save_mode,
            user_id=user_id,
            db=db
        )
        
        # 构建响应
        emotion_result = None
        if result.get("emotion"):
            emotion_data = result["emotion"]
            emotion_result = EmotionResult(
                emotion_type=emotion_data.get("emotion_type", "中性"),
                score=emotion_data.get("score", 50),
                confidence=emotion_data.get("confidence", 0.0),
                details=emotion_data.get("details")
            )
        
        return VoiceProcessWithOptionsResponse(
            success=result.get("success", False),
            status=result.get("status", "success"),
            task_id=result.get("task_id"),
            media_id=result.get("media_id"),
            media_url=result.get("media_url"),  # ✅ 添加音频URL
            text_media_id=result.get("text_media_id"),
            transcription_id=result.get("transcription_id"),
            original_text=result.get("original_text"),
            processed_text=result.get("processed_text"),
            emotion=emotion_result,
            error=result.get("error"),
            message=result.get("message"),
            asr_status=result.get("asr_status")
        )
    
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"语音处理失败: {str(e)}"
        )
    finally:
        # 清理临时文件
        # 注意：现在所有情况都需要清理临时文件（因为音频在 OSS 中，本地只是临时副本）
        if temp_file_path and os.path.exists(temp_file_path):
            try:
                os.remove(temp_file_path)
            except Exception as e:
                print(f"清理临时文件失败: {e}")


@router.post(
    "/transcribe",
    status_code=status.HTTP_200_OK,
    summary="语音转文字",
    description="仅执行语音转文字功能，不进行后续处理",
    responses={
        200: {"description": "转录成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误或音频格式不支持"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def transcribe_audio(
    audio: UploadFile = File(..., description="音频文件")
):
    """
    仅语音转文字
    
    验证需求: 8.1, 8.2, 8.3, 8.4
    
    Args:
        audio: 上传的音频文件
        
    Returns:
        dict: 包含转录文本的响应
    """
    temp_file_path = None
    
    try:
        # 验证文件是否存在
        if not audio.filename:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="未提供音频文件"
            )
        
        # 验证音频格式
        file_extension = os.path.splitext(audio.filename)[1].lower()
        supported_formats = ['.wav', '.mp3', '.m4a', '.ogg', '.flac', '.webm']
        
        if file_extension not in supported_formats:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"不支持的音频格式: {file_extension}。支持的格式: {', '.join(supported_formats)}"
            )
        
        # 保存临时音频文件
        media_service = MediaService()
        temp_file_relative_path = await media_service.save_file(audio, "audio")
        
        # 转换为绝对路径
        temp_file_path = str(media_service.upload_dir / temp_file_relative_path)
        
        # 执行语音转文字
        voice_service = VoiceProcessingService()
        transcribed_text = await voice_service.transcribe_audio(temp_file_path)
        
        # 返回转录结果
        return {
            "text": transcribed_text,
            "filename": audio.filename,
            "timestamp": datetime.now()
        }
    
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"语音转文字失败: {str(e)}"
        )
    finally:
        # 清理临时文件
        if temp_file_path and os.path.exists(temp_file_path):
            try:
                os.remove(temp_file_path)
            except Exception as e:
                print(f"清理临时文件失败: {e}")


@router.post(
    "/analyze-emotion",
    response_model=EmotionResult,
    status_code=status.HTTP_200_OK,
    summary="情感分析",
    description="仅对文本进行情感分析",
    responses={
        200: {"description": "情感分析成功"},
        400: {"model": ErrorResponse, "description": "请求参数错误"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def analyze_emotion(
    text: str = Form(..., description="待分析的文本")
):
    """
    仅情感分析
    
    验证需求: 10.1, 10.2, 10.3, 10.4, 10.6
    
    Args:
        text: 待分析的文本
        
    Returns:
        EmotionResult: 情感分析结果
    """
    try:
        # 验证文本不为空
        if not text or not text.strip():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="文本内容不能为空"
            )
        
        # 执行情感分析
        voice_service = VoiceProcessingService()
        emotion_result = await voice_service.analyze_emotion(text)
        
        # 返回情感分析结果
        return EmotionResult(
            emotion_type=emotion_result["emotion_type"],
            score=emotion_result["score"],
            confidence=emotion_result["confidence"],
            details=emotion_result.get("details")
        )
    
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"情感分析失败: {str(e)}"
        )


@router.get(
    "/asr-status",
    response_model=AsrStatusResponse,
    status_code=status.HTTP_200_OK,
    summary="查询 ASR 转写状态",
    description="""
    查询百度 ASR 任务的转写状态，用于前端轮询。
    
    返回状态：
    - success: 转写完成，返回文本
    - processing: 仍在处理中
    - failure: 转写失败，返回错误信息
    """,
    responses={
        200: {"description": "查询成功"},
        400: {"model": ErrorResponse, "description": "缺少 task_id 参数"},
        500: {"model": ErrorResponse, "description": "服务器内部错误"}
    }
)
async def get_asr_status(
    task_id: str
):
    """
    查询 ASR 转写状态
    
    前端在收到 status=processing 后，应轮询此接口获取最终结果。
    建议轮询间隔：3-5秒，最多 60 次（约 5 分钟）。
    
    Args:
        task_id: ASR 任务ID（从 /voice/process-with-options 返回）
        
    Returns:
        AsrStatusResponse: 包含状态和结果
    """
    try:
        if not task_id or not task_id.strip():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="缺少 task_id 参数"
            )
        
        # 获取外部 API 客户端
        external_api_client = get_external_api_client()
        
        # 查询 ASR 状态
        result = await external_api_client.query_asr_status(task_id)
        
        asr_status = result.get("status", "Unknown")
        
        if asr_status == "Success":
            return AsrStatusResponse(
                status="success",
                task_id=task_id,
                text=result.get("result", ""),
                asr_status=asr_status
            )
        elif asr_status == "Failure":
            return AsrStatusResponse(
                status="failure",
                task_id=task_id,
                error_code=result.get("error_code"),
                error_msg=result.get("error", "转写失败"),
                asr_status=asr_status
            )
        else:
            # Created/Running/Processing/Unknown 等都视为处理中
            return AsrStatusResponse(
                status="processing",
                task_id=task_id,
                asr_status=asr_status
            )
    
    except ASRAPIError as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"查询 ASR 状态失败: {str(e)}"
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"查询 ASR 状态失败: {str(e)}"
        )
