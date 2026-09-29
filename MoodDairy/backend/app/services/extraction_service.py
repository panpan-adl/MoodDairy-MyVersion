"""
日记提取服务层

提供日记结构化提取相关的业务逻辑处理，包括：
- 内容哈希计算
- 幂等性检查
- 内容收集
- 提取任务管理
- LLM 调用和结果验证
- 摘要查询和导出

验证需求: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 6.1, 6.2, 6.3
"""
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, or_, extract, func
from sqlalchemy.orm import selectinload, joinedload
from sqlalchemy.exc import IntegrityError, OperationalError, DatabaseError as SQLAlchemyDatabaseError
from typing import Optional, List, Dict, Any, Tuple
from datetime import date, datetime
import hashlib
import os
import json
import logging
import traceback

from app.models.database import Diary, DiaryMedia, VoiceTranscription, ExtractionJob, DiarySummary
from app.models.schemas import ExtractionResultSchema
from app.services.multimodal_insight_service import MultimodalInsightService
from app.services.extraction_errors import (
    ExtractionError,
    LLMAPIError,
    LLMTimeoutError,
    LLMRateLimitError,
    SchemaValidationError,
    SchemaRepairError,
    DatabaseOperationError,
    DatabaseConnectionError,
    DatabaseIntegrityError,
    ConcurrencyConflictError,
    DiaryNotFoundError,
    ContentHashError,
    ExtractionJobError,
    MaxRetriesExceededError,
    ContentCollectionError,
    ExportError,
    QueryError
)
from pydantic import ValidationError

# 配置日志
logger = logging.getLogger(__name__)

# 配置常量
MAX_RETRIES = int(os.getenv("EXTRACTION_MAX_RETRIES", "3"))
RETRY_DELAY = int(os.getenv("EXTRACTION_RETRY_DELAY", "60"))


class ExtractionService:
    """日记提取服务类"""
    
    def __init__(self, db: AsyncSession):
        """
        初始化提取服务
        
        Args:
            db: 数据库会话
        """
        self.db = db
        self.extract_version = int(os.getenv("EXTRACTION_VERSION", "1"))
    
    def _calculate_content_hash(self, diary: Diary) -> str:
        """
        计算日记内容的哈希值
        
        包含：标题、正文、媒体内容、语音转录
        使用 SHA256 算法
        
        Args:
            diary: 日记对象（需要预加载 media_items 和 voice_transcriptions）
            
        Returns:
            str: SHA256 哈希值（十六进制字符串）
            
        Raises:
            ContentHashError: 如果计算哈希失败
            
        验证需求: 1.2, 6.1
        """
        try:
            content_parts = []
            
            # 添加标题
            if diary.title:
                content_parts.append(diary.title)
            
            # 添加正文
            if diary.content:
                content_parts.append(diary.content)
            
            # 添加媒体内容（按 sort_order 排序以确保一致性）
            if diary.media_items:
                sorted_media = sorted(diary.media_items, key=lambda x: x.sort_order)
                for media in sorted_media:
                    if media.content:
                        content_parts.append(media.content)
            
            # 添加语音转录
            if diary.voice_transcriptions:
                for trans in diary.voice_transcriptions:
                    if trans.processed_text:
                        content_parts.append(trans.processed_text)
            
            # 合并所有内容并计算哈希
            combined = "\n".join(content_parts)
            hash_value = hashlib.sha256(combined.encode('utf-8')).hexdigest()
            
            logger.debug(f"计算内容哈希成功: diary_id={diary.id}, hash={hash_value[:8]}...")
            
            return hash_value
            
        except Exception as e:
            logger.error(f"计算内容哈希失败: diary_id={diary.id}, error={str(e)}")
            raise ContentHashError(diary_id=diary.id, original_error=e)
    
    async def should_extract(
        self, 
        diary: Diary, 
        force: bool = False,
        precomputed_hash: Optional[str] = None
    ) -> Tuple[bool, str]:
        """
        判断是否需要提取
        
        检查 extraction_status, content_hash, extract_version
        
        Args:
            diary: 日记对象（需要预加载 media_items 和 voice_transcriptions）
            force: 是否强制提取
            precomputed_hash: 可选，预计算好的内容哈希（如调用者已算过则传入，避免重复计算）
            
        Returns:
            Tuple[bool, str]: (是否需要提取, 原因说明)
            
        验证需求: 1.1, 1.2
        """
        if force:
            return True, "force=True"
        
        if diary.extraction_status == "processing":
            return False, "already processing"
        
        current_hash = precomputed_hash if precomputed_hash is not None else self._calculate_content_hash(diary)
        
        if (diary.extraction_status == "succeeded" and 
            diary.content_hash == current_hash and 
            diary.extract_version == self.extract_version):
            return False, "already extracted (same content and version)"
        
        if diary.content_hash != current_hash:
            return True, "content changed"
        
        if diary.extract_version != self.extract_version:
            return True, "version upgraded"
        
        if diary.extraction_status in ("pending", "failed"):
            return True, f"status={diary.extraction_status}"
        
        return False, "unknown"
    
    async def _collect_diary_content(self, diary: Diary) -> str:
        """
        收集日记的所有内容并格式化为统一字符串
        
        包括：
        - 日记标题和正文
        - 媒体项中的文本内容
        - 语音转录文本
        
        Args:
            diary: 日记对象（需要预加载 media_items 和 voice_transcriptions）
            
        Returns:
            str: 完整的日记内容字符串
            
        Raises:
            ContentCollectionError: 如果收集内容失败
            
        验证需求: 1.1, 6.1
        """
        try:
            content_parts = []
            
            # 添加标题
            if diary.title:
                content_parts.append(f"标题：{diary.title}")
            
            # 添加正文（如果有）
            if diary.content:
                content_parts.append(f"正文：{diary.content}")
            
            # 添加媒体项内容（按 sort_order 排序）
            if diary.media_items:
                sorted_media = sorted(diary.media_items, key=lambda x: x.sort_order)
                for media in sorted_media:
                    if media.media_type == "text" and media.content:
                        content_parts.append(f"文本：{media.content}")
            
            # 添加语音转录
            if diary.voice_transcriptions:
                for transcription in diary.voice_transcriptions:
                    if transcription.processed_text:
                        content_parts.append(f"语音转录：{transcription.processed_text}")
            
            collected_content = "\n\n".join(content_parts)
            
            # 验证收集到的内容不为空
            if not collected_content.strip():
                logger.warning(f"日记内容为空: diary_id={diary.id}")
                raise ContentCollectionError(
                    diary_id=diary.id,
                    missing_relations=["title", "content", "media_items", "voice_transcriptions"]
                )
            
            logger.debug(f"收集日记内容成功: diary_id={diary.id}, length={len(collected_content)}")
            
            return collected_content
            
        except ContentCollectionError:
            # 重新抛出已知错误
            raise
        except Exception as e:
            logger.error(f"收集日记内容失败: diary_id={diary.id}, error={str(e)}")
            raise ContentCollectionError(diary_id=diary.id, original_error=e)
    
    async def _create_extraction_job(
        self,
        diary_id: int,
        content_hash: str
    ) -> ExtractionJob:
        """
        创建提取任务记录
        
        Args:
            diary_id: 日记ID
            content_hash: 内容哈希值
            
        Returns:
            ExtractionJob: 创建的任务对象
            
        Raises:
            DatabaseOperationError: 如果数据库操作失败
            
        验证需求: 1.3, 6.2
        """
        try:
            job = ExtractionJob(
                diary_id=diary_id,
                status="pending",
                attempts=0,
                content_hash=content_hash,
                extract_version=self.extract_version,
                created_at=datetime.now()
            )
            
            self.db.add(job)
            await self.db.flush()  # 获取 job.id
            
            logger.info(f"创建提取任务: job_id={job.id}, diary_id={diary_id}")
            
            return job
            
        except IntegrityError as e:
            logger.error(f"创建提取任务失败（完整性错误）: diary_id={diary_id}, error={str(e)}")
            await self.db.rollback()
            raise DatabaseIntegrityError(
                message=f"创建提取任务失败：违反数据库约束",
                diary_id=diary_id,
                original_error=e
            )
        except OperationalError as e:
            logger.error(f"创建提取任务失败（操作错误）: diary_id={diary_id}, error={str(e)}")
            await self.db.rollback()
            raise DatabaseConnectionError(diary_id=diary_id, original_error=e)
        except Exception as e:
            logger.error(f"创建提取任务失败: diary_id={diary_id}, error={str(e)}")
            await self.db.rollback()
            raise DatabaseOperationError(
                message=f"创建提取任务失败",
                diary_id=diary_id,
                operation="create_extraction_job",
                original_error=e
            )
    
    async def _update_extraction_job(
        self,
        job_id: int,
        status: Optional[str] = None,
        attempts: Optional[int] = None,
        error_message: Optional[str] = None,
        error_code: Optional[str] = None,
        started_at: Optional[datetime] = None,
        execution_time_ms: Optional[int] = None
    ) -> None:
        """
        更新提取任务记录
        
        Args:
            job_id: 任务ID
            status: 任务状态
            attempts: 重试次数
            error_message: 错误信息
            error_code: 错误代码
            started_at: 开始时间
            execution_time_ms: 执行时间（毫秒）
            
        Raises:
            ExtractionJobError: 如果任务不存在或更新失败
            
        验证需求: 1.3, 6.2
        """
        try:
            stmt = select(ExtractionJob).where(ExtractionJob.id == job_id)
            result = await self.db.execute(stmt)
            job = result.scalar_one_or_none()
            
            if not job:
                logger.warning(f"提取任务不存在: job_id={job_id}")
                raise ExtractionJobError(
                    message=f"提取任务不存在",
                    job_id=job_id,
                    operation="update"
                )
            
            # 更新字段
            if status is not None:
                job.status = status
            if attempts is not None:
                job.attempts = attempts
            if error_message is not None:
                job.error_message = error_message
            if error_code is not None:
                job.error_code = error_code
            if started_at is not None:
                job.started_at = started_at
            if execution_time_ms is not None:
                job.execution_time_ms = execution_time_ms
            
            job.updated_at = datetime.now()
            
            await self.db.flush()
            
            logger.debug(f"更新提取任务: job_id={job_id}, status={status}")
            
        except ExtractionJobError:
            # 重新抛出已知错误
            raise
        except Exception as e:
            logger.error(f"更新提取任务失败: job_id={job_id}, error={str(e)}")
            await self.db.rollback()
            raise ExtractionJobError(
                message=f"更新提取任务失败",
                job_id=job_id,
                operation="update"
            )
    
    async def _complete_extraction_job(
        self,
        job_id: int,
        status: str,
        execution_time_ms: Optional[int] = None,
        error_message: Optional[str] = None,
        error_code: Optional[str] = None
    ) -> None:
        """
        完成提取任务记录
        
        Args:
            job_id: 任务ID
            status: 最终状态（succeeded/failed）
            execution_time_ms: 执行时间（毫秒）
            error_message: 错误信息（如果失败）
            error_code: 错误代码（如果失败）
            
        Raises:
            ExtractionJobError: 如果任务不存在或更新失败
            
        验证需求: 1.3, 6.2
        """
        try:
            stmt = select(ExtractionJob).where(ExtractionJob.id == job_id)
            result = await self.db.execute(stmt)
            job = result.scalar_one_or_none()
            
            if not job:
                logger.warning(f"提取任务不存在: job_id={job_id}")
                raise ExtractionJobError(
                    message=f"提取任务不存在",
                    job_id=job_id,
                    operation="complete"
                )
            
            job.status = status
            job.completed_at = datetime.now()
            
            if execution_time_ms is not None:
                job.execution_time_ms = execution_time_ms
            
            if error_message is not None:
                job.error_message = error_message
            
            if error_code is not None:
                job.error_code = error_code
            
            job.updated_at = datetime.now()
            
            await self.db.flush()
            
            logger.info(f"完成提取任务: job_id={job_id}, status={status}, time={execution_time_ms}ms")
            
        except ExtractionJobError:
            # 重新抛出已知错误
            raise
        except Exception as e:
            logger.error(f"完成提取任务失败: job_id={job_id}, error={str(e)}")
            await self.db.rollback()
            raise ExtractionJobError(
                message=f"完成提取任务失败",
                job_id=job_id,
                operation="complete"
            )
    
    async def _get_diary_with_relations(self, diary_id: int) -> Optional[Diary]:
        """
        查询日记及其关联数据
        
        预加载 media_items 和 voice_transcriptions
        
        Args:
            diary_id: 日记ID
            
        Returns:
            Optional[Diary]: 日记对象，如果不存在则返回None
            
        Raises:
            DatabaseOperationError: 如果数据库查询失败
            
        验证需求: 6.2
        """
        try:
            stmt = (
                select(Diary)
                .options(
                    selectinload(Diary.media_items),
                    selectinload(Diary.voice_transcriptions)
                )
                .where(Diary.id == diary_id)
            )
            
            result = await self.db.execute(stmt)
            diary = result.scalar_one_or_none()
            
            if diary:
                logger.debug(f"查询日记成功: diary_id={diary_id}")
            else:
                logger.debug(f"日记不存在: diary_id={diary_id}")
            
            return diary
            
        except OperationalError as e:
            logger.error(f"查询日记失败（数据库连接错误）: diary_id={diary_id}, error={str(e)}")
            raise DatabaseConnectionError(diary_id=diary_id, original_error=e)
        except Exception as e:
            logger.error(f"查询日记失败: diary_id={diary_id}, error={str(e)}")
            raise DatabaseOperationError(
                message=f"查询日记失败",
                diary_id=diary_id,
                operation="get_diary_with_relations",
                original_error=e
            )

    async def _repair_extraction_result(
        self,
        diary_content: str,
        invalid_result: dict,
        validation_errors: List[Dict]
    ) -> dict:
        """
        修复 LLM 输出的提取结果
        
        当 LLM 输出不符合 Schema 时，尝试修复
        
        策略:
        1. 分析验证错误
        2. 构建修复 Prompt
        3. 调用 LLM 重新生成
        4. 如果仍然失败，使用默认值
        
        Args:
            diary_content: 原始日记内容
            invalid_result: 无效的提取结果
            validation_errors: Pydantic 验证错误列表
            
        Returns:
            dict: 修复后的结果
            
        Raises:
            SchemaRepairError: 如果修复失败且无法使用默认值
            
        验证需求: 1.4, 6.1
        """
        logger.warning(f"开始修复提取结果，错误数: {len(validation_errors)}")
        
        try:
            # 构建错误描述
            error_descriptions = []
            for error in validation_errors:
                loc = " -> ".join(str(x) for x in error['loc'])
                msg = error['msg']
                error_descriptions.append(f"- {loc}: {msg}")
            
            errors_text = "\n".join(error_descriptions)
            
            # 构建修复 Prompt
            repair_prompt = f"""你之前生成的提取结果存在以下问题：

{errors_text}

原始结果：
```json
{json.dumps(invalid_result, ensure_ascii=False, indent=2)}
```

请修复这些问题，返回符合要求的 JSON 结果。

要求：
1. 修复所有验证错误
2. 保持其他正确的字段不变
3. 确保 JSON 格式正确
4. 只返回 JSON，不要添加任何说明

Schema 要求：
- summary: 字符串，10-500字
- keywords: 字符串数组，1-10个
- main_topics: 字符串数组，0-10个
- people_mentioned: 对象数组，每个对象包含 name 和 relation 字段
- places_mentioned: 对象数组，每个对象包含 name 和 type 字段
- emotion_analysis.primary_emotion: 必须是以下之一：开心、平静、焦虑、愤怒、低落、兴奋、复杂、中性
- emotion_analysis.emotion_score: 整数，1-100
- emotion_analysis.emotion_intensity: 必须是以下之一：轻微、中等、强烈
- emotion_analysis.emotion_distribution: 对象，各项之和为1.0
- emotion_analysis.key_sentences: 字符串数组，最多5个
- has_highlight: 0或1
- has_small_happiness: 0或1
- 如果 has_highlight=1，必须提供 highlight_summary（非空字符串）
- 如果 has_highlight=0，highlight_summary 必须为 null
- 如果 has_small_happiness=1，必须提供 small_happiness_content（非空字符串）
- 如果 has_small_happiness=0，small_happiness_content 必须为 null

情绪评分与主要情绪的一致性：
- 积极情绪（开心、兴奋）的评分应 >= 56
- 消极情绪（低落、愤怒、焦虑）的评分应 <= 45
- 中性或复杂情绪的评分在 46-55 之间
"""
            
            messages = [
                {
                    "role": "system",
                    "content": "你是一个专业的数据修复助手。你的任务是修复不符合 Schema 的 JSON 数据。只返回修复后的 JSON，不要添加任何说明。"
                },
                {
                    "role": "user",
                    "content": repair_prompt
                }
            ]
            
            try:
                # 导入 external_api_client
                from app.services.external_api_client import get_external_api_client
                
                external_api_client = get_external_api_client()
                
                # 调用 LLM 修复（使用内部的 QianfanClient）
                logger.info("调用 LLM 进行修复...")
                response_data = await external_api_client._qianfan_client._call_llm(
                    messages=messages,
                    temperature=0.1,  # 低温度，更确定
                    max_tokens=2000
                )
                
                repaired_result = external_api_client._qianfan_client._parse_json_response(response_data)
                
                # 验证修复结果
                try:
                    ExtractionResultSchema(**repaired_result)
                    logger.info("修复成功")
                    return repaired_result
                except ValidationError as e:
                    logger.error(f"修复后仍然无效: {str(e)}")
                    # 使用默认值
                    logger.warning("使用默认提取结果")
                    return self._get_default_extraction_result(diary_content)
                    
            except Exception as e:
                logger.error(f"修复过程失败: {str(e)}, traceback={traceback.format_exc()}")
                # 使用默认值
                logger.warning("使用默认提取结果")
                return self._get_default_extraction_result(diary_content)
                
        except Exception as e:
            logger.error(f"修复流程异常: {str(e)}, traceback={traceback.format_exc()}")
            raise SchemaRepairError(
                message=f"修复提取结果失败",
                original_errors=validation_errors,
                repair_attempts=1
            )
    
    def _get_default_extraction_result(self, diary_content: str) -> dict:
        """
        获取默认的提取结果（当 LLM 完全失败时使用）
        
        提供安全的降级结果
        
        Args:
            diary_content: 日记内容
            
        Returns:
            dict: 默认的提取结果
            
        验证需求: 1.4
        """
        # 简单的关键词提取（使用前100个字符作为摘要）
        preview = diary_content[:100] if len(diary_content) > 100 else diary_content
        
        # 确保摘要至少有10个字符（Schema 要求）
        if len(preview) < 10:
            preview = preview + "..." * ((10 - len(preview)) // 3 + 1)
            preview = preview[:10]
        
        return {
            "summary": preview,
            "keywords": ["日记"],
            "main_topics": [],
            "people_mentioned": [],
            "places_mentioned": [],
            "emotion_analysis": {
                "primary_emotion": "中性",
                "emotion_score": 50,
                "emotion_intensity": "中等",
                "emotion_distribution": {
                    "开心": 0.0,
                    "平静": 0.0,
                    "焦虑": 0.0,
                    "愤怒": 0.0,
                    "低落": 0.0,
                    "兴奋": 0.0,
                    "复杂": 0.0,
                    "中性": 1.0
                },
                "key_sentences": []
            },
            "has_highlight": 0,
            "highlight_summary": None,
            "has_small_happiness": 0,
            "small_happiness_content": None
        }
    
    async def extract_diary(
        self, 
        diary_id: int, 
        force: bool = False
    ) -> Optional[Dict[str, Any]]:
        """
        提取日记内容并生成摘要（V1版本）
        
        完整流程:
        1. 查询日记及关联数据
        2. 检查是否需要提取（幂等性）
        3. 创建提取任务记录
        4. 更新状态为 processing
        5. 收集内容并计算哈希
        6. 调用 LLM
        7. Schema 验证（失败则修复）
        8. 存储结果
        9. 更新状态和标记
        10. 完成任务记录
        
        Args:
            diary_id: 日记ID
            force: 是否强制提取
            
        Returns:
            Optional[Dict[str, Any]]: 提取结果字典，如果跳过提取则返回None
            
        Raises:
            DiaryNotFoundError: 如果日记不存在
            ConcurrencyConflictError: 如果存在并发冲突
            LLMAPIError: 如果 LLM API 调用失败
            SchemaValidationError: 如果 Schema 验证失败且无法修复
            DatabaseOperationError: 如果数据库操作失败
            ExtractionError: 其他提取过程中的错误
            
        验证需求: 1.1, 1.2, 1.3, 1.4, 6.1, 6.2, 6.3
        """
        start_time = datetime.now()
        logger.info(f"========== 开始提取日记 ==========")
        logger.info(f"diary_id={diary_id}, force={force}, time={start_time.isoformat()}")
        
        job = None
        
        try:
            # 1. 查询日记及关联数据
            logger.info(f"[步骤 1/11] 查询日记及关联数据...")
            diary = await self._get_diary_with_relations(diary_id)
            if not diary:
                logger.error(f"日记不存在: diary_id={diary_id}")
                raise DiaryNotFoundError(diary_id=diary_id)
            
            from app.security.field_crypto import decrypt_diary_fields
            decrypt_diary_fields(diary)
            
            logger.info(f"日记查询成功: user_id={diary.user_id}")
            
            # 预计算哈希，避免在 should_extract 中重复计算
            logger.info(f"[步骤 2/11] 计算内容哈希...")
            content_hash = self._calculate_content_hash(diary)
            logger.info(f"内容哈希: {content_hash[:16]}...")
            
            # 3. 检查是否需要提取（幂等性）
            logger.info(f"[步骤 3/11] 检查幂等性...")
            should_extract, reason = await self.should_extract(diary, force, precomputed_hash=content_hash)
            if not should_extract:
                logger.info(f"跳过提取: diary_id={diary_id}, reason={reason}")
                return None
            
            logger.info(f"需要提取: reason={reason}")
            
            # 检查并发冲突
            if diary.extraction_status == "processing" and not force:
                logger.warning(f"并发冲突: diary_id={diary_id}, status={diary.extraction_status}")
                raise ConcurrencyConflictError(
                    diary_id=diary_id,
                    current_status=diary.extraction_status,
                    attempted_operation="extract"
                )
            
            # 4. 创建提取任务记录
            logger.info(f"[步骤 4/11] 创建提取任务记录...")
            job = await self._create_extraction_job(diary_id, content_hash)
            logger.info(f"任务创建成功: job_id={job.id}")
            
            try:
                # 5. 更新日记状态为 processing
                logger.info(f"[步骤 5/11] 更新日记状态为 processing...")
                diary.extraction_status = "processing"
                diary.content_hash = content_hash
                diary.extract_version = self.extract_version
                await self.db.commit()
                logger.info("日记状态更新成功")
                
                # 更新任务状态为 processing
                await self._update_extraction_job(
                    job_id=job.id,
                    status="processing",
                    started_at=datetime.now(),
                    attempts=1
                )
                await self.db.commit()
                logger.info("任务状态更新成功")
                
                # 6. 收集内容
                logger.info(f"[步骤 6/11] 收集日记内容...")
                diary_content = await self._collect_diary_content(diary)
                logger.info(f"内容收集成功: length={len(diary_content)} chars")
                
                # 7. 调用 LLM
                logger.info(f"[步骤 7/11] 调用 LLM 进行提取...")
                llm_start_time = datetime.now()
                
                try:
                    # 导入 external_api_client
                    from app.services.external_api_client import get_external_api_client
                    external_api_client = get_external_api_client()
                    
                    llm_result = await external_api_client.extract_diary_structure(
                        diary_content=diary_content,
                        diary_date=diary.diary_date.isoformat() if diary.diary_date else "",
                        weather=diary.weather or "",
                        location=diary.location or "",
                        mood_score=diary.mood_score or 50
                    )
                    
                    execution_time = int((datetime.now() - llm_start_time).total_seconds() * 1000)
                    logger.info(f"LLM 调用成功: time={execution_time}ms")
                    
                except Exception as e:
                    logger.error(f"LLM 调用失败: error={str(e)}, traceback={traceback.format_exc()}")
                    raise LLMAPIError(
                        message=f"LLM API 调用失败: {str(e)}",
                        diary_id=diary_id
                    )
                
                # 8. Schema 验证（失败则修复）
                logger.info(f"[步骤 8/11] 验证 Schema...")
                try:
                    validated_result = ExtractionResultSchema(**llm_result)
                    logger.info("Schema 验证成功")
                except ValidationError as e:
                    logger.warning(f"Schema 验证失败，尝试修复: errors={len(e.errors())}")
                    logger.debug(f"验证错误详情: {e.errors()}")
                    
                    # 修复流程
                    try:
                        repaired_result = await self._repair_extraction_result(
                            diary_content=diary_content,
                            invalid_result=llm_result,
                            validation_errors=e.errors()
                        )
                        validated_result = ExtractionResultSchema(**repaired_result)
                        logger.info("Schema 修复成功")
                    except ValidationError as repair_error:
                        logger.error(f"Schema 修复失败: {str(repair_error)}")
                        raise SchemaValidationError(
                            message=f"Schema 验证失败且无法修复",
                            diary_id=diary_id,
                            validation_errors=e.errors(),
                            invalid_data=llm_result
                        )
                
                # 9. 存储结果
                logger.info(f"[步骤 9/11] 存储提取结果...")
                await self._save_extraction_result(diary_id, validated_result)
                try:
                    await MultimodalInsightService(self.db).analyze_diary(diary_id)
                    logger.info("Multimodal insight generated: diary_id=%s", diary_id)
                except Exception as insight_error:
                    logger.warning(
                        "Multimodal insight generation failed but extraction continues: diary_id=%s error=%s",
                        diary_id,
                        insight_error,
                    )
                logger.info("提取结果存储成功")
                
                # 10. 更新日记状态和标记
                logger.info(f"[步骤 10/11] 更新日记状态和标记...")
                diary.extraction_status = "succeeded"
                diary.is_extracted = 1
                diary.is_highlight = validated_result.has_highlight
                diary.is_little_joy = validated_result.has_small_happiness
                await self.db.commit()
                logger.info("日记状态更新成功")
                
                # 11. 完成任务记录
                logger.info(f"[步骤 11/11] 完成任务记录...")
                await self._complete_extraction_job(
                    job_id=job.id,
                    status="succeeded",
                    execution_time_ms=execution_time
                )
                await self.db.commit()
                logger.info("任务记录完成")
                
                total_time = int((datetime.now() - start_time).total_seconds() * 1000)
                logger.info(f"========== 提取完成 ==========")
                logger.info(f"diary_id={diary_id}, total_time={total_time}ms, llm_time={execution_time}ms")
                
                return validated_result.model_dump()
                
            except Exception as e:
                # 内部错误处理
                logger.error(f"提取过程异常: diary_id={diary_id}, error={str(e)}")
                logger.error(f"错误堆栈: {traceback.format_exc()}")
                
                # 更新日记状态
                try:
                    diary.extraction_status = "failed"
                    await self.db.commit()
                    logger.info("日记状态已更新为 failed")
                except Exception as commit_error:
                    logger.error(f"更新日记状态失败: {str(commit_error)}")
                    await self.db.rollback()
                
                # 更新任务记录
                if job:
                    try:
                        await self._complete_extraction_job(
                            job_id=job.id,
                            status="failed",
                            error_message=str(e),
                            error_code=type(e).__name__
                        )
                        await self.db.commit()
                        logger.info("任务记录已更新为 failed")
                    except Exception as job_error:
                        logger.error(f"更新任务记录失败: {str(job_error)}")
                        await self.db.rollback()
                
                # 重新抛出异常
                raise
                
        except (DiaryNotFoundError, ConcurrencyConflictError, LLMAPIError, 
                SchemaValidationError, DatabaseOperationError, ExtractionError):
            # 重新抛出已知的提取错误
            raise
        except Exception as e:
            # 捕获未预期的错误
            logger.error(f"未预期的错误: diary_id={diary_id}, error={str(e)}")
            logger.error(f"错误堆栈: {traceback.format_exc()}")
            
            # 包装为通用提取错误
            raise ExtractionError(
                message=f"提取过程发生未预期的错误: {str(e)}",
                error_code="UNEXPECTED_ERROR",
                diary_id=diary_id,
                details={"original_error": str(e), "error_type": type(e).__name__}
            )
    
    async def _save_extraction_result(
        self,
        diary_id: int,
        result: ExtractionResultSchema
    ) -> None:
        """
        存储提取结果到 diary_summaries 表
        
        创建或更新 diary_summaries 记录
        提取情绪字段到独立列
        
        Args:
            diary_id: 日记ID
            result: 验证后的提取结果
            
        Raises:
            DatabaseOperationError: 如果数据库操作失败
            
        验证需求: 2.1, 6.2
        """
        try:
            # 查询是否已存在摘要记录
            stmt = select(DiarySummary).where(DiarySummary.diary_id == diary_id)
            db_result = await self.db.execute(stmt)
            summary = db_result.scalar_one_or_none()
            
            # 准备情绪分析数据
            emotion_data = result.emotion_analysis
            
            # 构建完整的情绪分析 JSONB（保留详细信息）
            emotion_analysis_json = {
                "primary_emotion": emotion_data.primary_emotion.value,
                "emotion_score": emotion_data.emotion_score,
                "emotion_intensity": emotion_data.emotion_intensity.value,
                "emotion_distribution": emotion_data.emotion_distribution,
                "key_sentences": emotion_data.key_sentences
            }
            
            # 转换人物和地点为 JSONB 格式
            people_json = [p.model_dump() for p in result.people_mentioned]
            places_json = [p.model_dump() for p in result.places_mentioned]
            
            if summary:
                # 更新现有记录
                logger.debug(f"更新现有摘要记录: diary_id={diary_id}")
                
                summary.summary = result.summary
                summary.keywords = result.keywords
                summary.main_topics = result.main_topics
                summary.people_mentioned = people_json
                summary.places_mentioned = places_json
                
                # 更新情绪字段（独立列）
                summary.primary_emotion = emotion_data.primary_emotion.value
                summary.emotion_score = emotion_data.emotion_score
                summary.emotion_intensity = emotion_data.emotion_intensity.value
                summary.emotion_distribution = emotion_data.emotion_distribution
                summary.emotion_analysis = emotion_analysis_json
                
                # 更新高光时刻和小确幸
                summary.has_highlight = result.has_highlight
                summary.highlight_summary = result.highlight_summary
                summary.has_small_happiness = result.has_small_happiness
                summary.small_happiness_content = result.small_happiness_content
                
                # 更新元数据
                summary.extract_version = self.extract_version
                summary.updated_at = datetime.now()
                
                logger.info(f"更新摘要记录成功: diary_id={diary_id}")
            else:
                # 创建新记录
                logger.debug(f"创建新摘要记录: diary_id={diary_id}")
                
                summary = DiarySummary(
                    diary_id=diary_id,
                    summary=result.summary,
                    keywords=result.keywords,
                    main_topics=result.main_topics,
                    people_mentioned=people_json,
                    places_mentioned=places_json,
                    
                    # 情绪字段（独立列）
                    primary_emotion=emotion_data.primary_emotion.value,
                    emotion_score=emotion_data.emotion_score,
                    emotion_intensity=emotion_data.emotion_intensity.value,
                    emotion_distribution=emotion_data.emotion_distribution,
                    emotion_analysis=emotion_analysis_json,
                    
                    # 高光时刻和小确幸
                    has_highlight=result.has_highlight,
                    highlight_summary=result.highlight_summary,
                    has_small_happiness=result.has_small_happiness,
                    small_happiness_content=result.small_happiness_content,
                    
                    # 元数据
                    extract_version=self.extract_version,
                    created_at=datetime.now(),
                    updated_at=datetime.now()
                )
                
                self.db.add(summary)
                logger.info(f"创建摘要记录成功: diary_id={diary_id}")
            
            await self.db.flush()
            
        except IntegrityError as e:
            logger.error(f"存储提取结果失败（完整性错误）: diary_id={diary_id}, error={str(e)}")
            await self.db.rollback()
            raise DatabaseIntegrityError(
                message=f"存储提取结果失败：违反数据库约束",
                diary_id=diary_id,
                original_error=e
            )
        except Exception as e:
            logger.error(f"存储提取结果失败: diary_id={diary_id}, error={str(e)}")
            await self.db.rollback()
            raise DatabaseOperationError(
                message=f"存储提取结果失败",
                diary_id=diary_id,
                operation="save_extraction_result",
                original_error=e
            )

    async def get_summaries(
        self,
        user_id: int,
        start_date: Optional[date] = None,
        end_date: Optional[date] = None,
        keywords: Optional[List[str]] = None,
        emotions: Optional[List[str]] = None,
        limit: int = 10
    ) -> List[Dict[str, Any]]:
        """
        查询日记摘要（供大模型使用）
        
        支持日期范围过滤、关键词过滤、情绪过滤
        返回简化格式
        
        优化策略：
        1. 只返回必要字段（简化版）
        2. 限制返回数量
        3. 按日期倒序排列（最近的优先）
        4. 使用索引加速查询
        
        Args:
            user_id: 用户ID
            start_date: 开始日期
            end_date: 结束日期
            keywords: 关键词列表
            emotions: 情绪列表
            limit: 返回数量限制（1-100）
            
        Returns:
            List[Dict[str, Any]]: 摘要列表（简化格式）
            
        Raises:
            QueryError: 如果查询失败
            
        验证需求: 3.1, 3.2, 3.3, 6.2, 6.3
        """
        logger.info(f"查询摘要: user_id={user_id}, start_date={start_date}, end_date={end_date}, "
                   f"keywords={keywords}, emotions={emotions}, limit={limit}")
        
        try:
            # 构建基础查询
            query = (
                select(DiarySummary, Diary.diary_date)
                .join(Diary, DiarySummary.diary_id == Diary.id)
                .where(Diary.user_id == user_id)
                .order_by(Diary.diary_date.desc())
                .limit(limit)
            )
            
            # 添加日期范围过滤
            if start_date:
                query = query.where(Diary.diary_date >= start_date)
                logger.debug(f"添加开始日期过滤: {start_date}")
            if end_date:
                query = query.where(Diary.diary_date <= end_date)
                logger.debug(f"添加结束日期过滤: {end_date}")
            
            # 添加关键词过滤（PostgreSQL ARRAY @> 操作符）
            if keywords:
                for keyword in keywords:
                    query = query.where(DiarySummary.keywords.contains([keyword]))
                logger.debug(f"添加关键词过滤: {keywords}")
            
            # 添加情绪过滤
            if emotions:
                query = query.where(DiarySummary.primary_emotion.in_(emotions))
                logger.debug(f"添加情绪过滤: {emotions}")
            
            # 执行查询
            result = await self.db.execute(query)
            rows = result.all()
            
            # 转换为简化格式
            summaries = []
            for summary, diary_date in rows:
                summaries.append({
                    "diary_id": summary.diary_id,
                    "diary_date": diary_date.isoformat(),
                    "summary": summary.summary or "",
                    "keywords": summary.keywords or [],
                    "primary_emotion": summary.primary_emotion or "中性",
                    "emotion_score": summary.emotion_score or 50
                })
            
            logger.info(f"查询摘要成功: user_id={user_id}, count={len(summaries)}")
            
            return summaries
            
        except OperationalError as e:
            logger.error(f"查询摘要失败（数据库连接错误）: user_id={user_id}, error={str(e)}")
            raise DatabaseConnectionError(original_error=e)
        except Exception as e:
            logger.error(f"查询摘要失败: user_id={user_id}, error={str(e)}, traceback={traceback.format_exc()}")
            raise QueryError(
                message=f"查询摘要失败",
                user_id=user_id,
                query_params={
                    "start_date": start_date.isoformat() if start_date else None,
                    "end_date": end_date.isoformat() if end_date else None,
                    "keywords": keywords,
                    "emotions": emotions,
                    "limit": limit
                },
                original_error=e
            )
    
    async def export_summaries(
        self,
        user_id: int,
        year: int,
        month: Optional[int] = None,
        format: str = "json"
    ) -> str:
        """
        导出摘要文件
        
        支持 JSON 格式和 Markdown 格式
        按年/月导出
        
        文件格式（JSON）：
        {
            "user_id": 1,
            "export_date": "2026-01-17T10:00:00",
            "period": "2026-01",
            "total_count": 15,
            "summaries": [...]
        }
        
        Args:
            user_id: 用户ID（从认证上下文获取，不允许传参）
            year: 年份
            month: 月份（可选）
            format: 文件格式（json/markdown）
            
        Returns:
            str: 文件路径
            
        Raises:
            ExportError: 如果导出失败
            
        验证需求: 2.1, 2.2, 2.3, 6.2, 6.3
        """
        logger.info(f"导出摘要: user_id={user_id}, year={year}, month={month}, format={format}")
        
        try:
            # 构建查询
            query = (
                select(DiarySummary, Diary)
                .join(Diary, DiarySummary.diary_id == Diary.id)
                .where(Diary.user_id == user_id)
                .where(extract('year', Diary.diary_date) == year)
            )
            
            if month:
                query = query.where(extract('month', Diary.diary_date) == month)
            
            query = query.order_by(Diary.diary_date.asc())
            
            # 执行查询
            logger.debug("执行数据库查询...")
            result = await self.db.execute(query)
            rows = result.all()
            logger.info(f"查询到 {len(rows)} 条记录")
            
            # 构建导出数据
            export_data = {
                "user_id": user_id,
                "export_date": datetime.now().isoformat(),
                "period": f"{year}-{month:02d}" if month else str(year),
                "total_count": len(rows),
                "summaries": []
            }
            
            for summary, diary in rows:
                export_data["summaries"].append({
                    "diary_id": summary.diary_id,
                    "diary_date": diary.diary_date.isoformat(),
                    "title": diary.title,
                    "summary": summary.summary,
                    "keywords": summary.keywords or [],
                    "main_topics": summary.main_topics or [],
                    "people_mentioned": summary.people_mentioned or [],
                    "places_mentioned": summary.places_mentioned or [],
                    "primary_emotion": summary.primary_emotion,
                    "emotion_score": summary.emotion_score,
                    "emotion_intensity": summary.emotion_intensity,
                    "emotion_distribution": summary.emotion_distribution,
                    "has_highlight": summary.has_highlight,
                    "highlight_summary": summary.highlight_summary,
                    "has_small_happiness": summary.has_small_happiness,
                    "small_happiness_content": summary.small_happiness_content
                })
            
            # 生成文件
            period_str = f"{year}_{month:02d}" if month else str(year)
            
            try:
                if format == "json":
                    filename = f"diary_summaries_{user_id}_{period_str}.json"
                    filepath = os.path.join("/tmp", filename)
                    
                    logger.debug(f"写入 JSON 文件: {filepath}")
                    with open(filepath, "w", encoding="utf-8") as f:
                        json.dump(export_data, f, ensure_ascii=False, indent=2)
                    
                    logger.info(f"导出JSON摘要成功: user_id={user_id}, period={export_data['period']}, count={len(rows)}, file={filepath}")
                
                elif format == "markdown":
                    filename = f"diary_summaries_{user_id}_{period_str}.md"
                    filepath = os.path.join("/tmp", filename)
                    
                    logger.debug(f"写入 Markdown 文件: {filepath}")
                    with open(filepath, "w", encoding="utf-8") as f:
                        # 写入标题
                        f.write(f"# 日记摘要 - {export_data['period']}\n\n")
                        f.write(f"导出时间: {export_data['export_date']}\n")
                        f.write(f"总计: {export_data['total_count']} 篇日记\n\n")
                        f.write("---\n\n")
                        
                        # 写入每篇日记的摘要
                        for item in export_data["summaries"]:
                            f.write(f"## {item['diary_date']}")
                            if item['title']:
                                f.write(f" - {item['title']}")
                            f.write("\n\n")
                            
                            # 摘要
                            if item['summary']:
                                f.write(f"**摘要**: {item['summary']}\n\n")
                            
                            # 关键词
                            if item['keywords']:
                                f.write(f"**关键词**: {', '.join(item['keywords'])}\n\n")
                            
                            # 情绪
                            if item['primary_emotion']:
                                f.write(f"**情绪**: {item['primary_emotion']} ({item['emotion_score']}/100, {item['emotion_intensity']})\n\n")
                            
                            # 主要话题
                            if item['main_topics']:
                                f.write(f"**主要话题**: {', '.join(item['main_topics'])}\n\n")
                            
                            # 提及的人物
                            if item['people_mentioned']:
                                people_str = ", ".join([f"{p['name']}({p['relation']})" for p in item['people_mentioned']])
                                f.write(f"**提及人物**: {people_str}\n\n")
                            
                            # 提及的地点
                            if item['places_mentioned']:
                                places_str = ", ".join([f"{p['name']}({p['type']})" for p in item['places_mentioned']])
                                f.write(f"**提及地点**: {places_str}\n\n")
                            
                            # 高光时刻
                            if item['has_highlight'] == 1 and item['highlight_summary']:
                                f.write(f"✨ **高光时刻**: {item['highlight_summary']}\n\n")
                            
                            # 小确幸
                            if item['has_small_happiness'] == 1 and item['small_happiness_content']:
                                f.write(f"🌟 **小确幸**: {item['small_happiness_content']}\n\n")
                            
                            f.write("---\n\n")
                    
                    logger.info(f"导出Markdown摘要成功: user_id={user_id}, period={export_data['period']}, count={len(rows)}, file={filepath}")
                
                else:
                    logger.error(f"不支持的导出格式: {format}")
                    raise ExportError(
                        message=f"不支持的导出格式: {format}",
                        user_id=user_id,
                        export_format=format,
                        period=export_data['period']
                    )
                
                return filepath
                
            except IOError as e:
                logger.error(f"文件写入失败: {str(e)}")
                raise ExportError(
                    message=f"文件写入失败: {str(e)}",
                    user_id=user_id,
                    export_format=format,
                    period=export_data['period'],
                    original_error=e
                )
                
        except ExportError:
            # 重新抛出已知错误
            raise
        except OperationalError as e:
            logger.error(f"导出摘要失败（数据库连接错误）: user_id={user_id}, error={str(e)}")
            raise DatabaseConnectionError(original_error=e)
        except Exception as e:
            logger.error(f"导出摘要失败: user_id={user_id}, error={str(e)}, traceback={traceback.format_exc()}")
            raise ExportError(
                message=f"导出摘要失败",
                user_id=user_id,
                export_format=format,
                period=f"{year}-{month:02d}" if month else str(year),
                original_error=e
            )
