"""
日记服务层

提供日记相关的业务逻辑处理，包括：
- 日记CRUD操作
- 媒体项管理
- 数据库查询优化
- 自动触发提取任务
"""
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import and_, delete, extract, func, or_, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import selectinload, joinedload
from typing import Callable, Optional, List
from datetime import date
import asyncio
import logging

from app.models.database import Diary, DiaryMedia
from app.models.schemas import CreateDiaryRequest, UpdateDiaryRequest, AddMediaRequest, SyncMediaItemRequest
from app.security.field_crypto import decrypt_diary_fields, encrypt_diary_fields, encrypt_field, decrypt_field

logger = logging.getLogger(__name__)


class DiaryService:
    """日记服务类"""
    
    def __init__(self, db: AsyncSession):
        """
        初始化日记服务
        
        Args:
            db: 数据库会话
        """
        self.db = db
        self._media_files_table_available = None

    @staticmethod
    def _decrypt_diaries_list(diaries: List[Diary]) -> List[Diary]:
        for diary in diaries:
            decrypt_diary_fields(diary)
        return diaries

    async def _media_files_table_exists(self) -> bool:
        """Probe whether the optional media_files table is available."""

        if self._media_files_table_available is not None:
            return self._media_files_table_available

        try:
            result = await self.db.execute(text("SELECT to_regclass('public.media_files')"))
            self._media_files_table_available = bool(result.scalar())
        except Exception as exc:
            logger.warning("Failed to probe media_files availability: %s", exc)
            self._media_files_table_available = False
            await self.db.rollback()

        return self._media_files_table_available

    @staticmethod
    def _can_skip_media_files_error(exc: Exception) -> bool:
        error_text = str(exc).lower()
        return "media_files" in error_text and any(
            marker in error_text
            for marker in ("does not exist", "undefinedtable", "permission denied", "insufficientprivilege")
        )

    def _launch_extraction_worker(
        self,
        worker: Callable[[int, bool], object],
        diary_id: int,
        force: bool,
    ) -> None:
        asyncio.create_task(asyncio.to_thread(worker, diary_id, force))

    def _trigger_extraction_async(self, diary_id: int, force: bool = False):
        """
        异步触发提取任务（不阻塞主流程）

        使用 asyncio.create_task() 在后台触发 Celery 任务

        Args:
            diary_id: 日记ID
            force: 是否强制提取

        验证需求: 1.1
        """
        try:
            # 导入 trigger_extraction 函数
            from app.celery_app import _run_extraction, extraction_queue_available, trigger_extraction

            # 在后台触发提取任务（不等待结果）
            if extraction_queue_available():
                self._launch_extraction_worker(trigger_extraction, diary_id, force)
            else:
                self._launch_extraction_worker(_run_extraction, diary_id, force)

            logger.info(f"已触发提取任务: diary_id={diary_id}, force={force}")
        except Exception as e:
            # 提取失败不影响日记保存
            logger.error(f"触发提取任务失败: diary_id={diary_id}, error={str(e)}")

    def _trigger_embedding_async(self, diary_id: int, force: bool = False):
        """
        异步触发向量化任务（不阻塞主流程）

        使用 asyncio.create_task() 在后台调用 DiaryEmbeddingService

        Args:
            diary_id: 日记ID
            force: 是否强制重新生成
        """
        try:
            from app.services.diary_embedding_service import DiaryEmbeddingService

            async def run_embedding():
                from app.database import async_session_maker
                async with async_session_maker() as session:
                    service = DiaryEmbeddingService(session)
                    await service.generate_embedding(diary_id, force=force)

            asyncio.create_task(run_embedding())
            logger.info(f"已触发向量化任务: diary_id={diary_id}, force={force}")
        except Exception as e:
            # 向量化失败不影响日记保存
            logger.error(f"触发向量化任务失败: diary_id={diary_id}, error={str(e)}")
    
    async def create_diary(self, data: CreateDiaryRequest) -> Diary:
        """
        创建日记记录
        
        Args:
            data: 创建日记请求数据
            
        Returns:
            Diary: 创建的日记对象
            
        验证需求: 2.1, 2.4, 7.4, 1.1（自动触发提取）
        """
        # 创建日记对象
        enc_title, enc_content = encrypt_diary_fields(data.title, data.content)
        plain_content = data.content or ""
        diary = Diary(
            user_id=data.user_id,
            title=enc_title,
            content=enc_content,
            diary_date=data.diary_date,
            weather=data.weather,
            location=data.location,
            mood_score=data.mood_score,
            mood_type=data.mood_type,
            is_private=data.is_private if data.is_private is not None else 1,
            word_count=len(plain_content)
        )
        
        # 添加到数据库
        self.db.add(diary)
        await self.db.commit()
        await self.db.refresh(diary)
        
        # 预加载media_items关系以避免lazy loading错误
        # 新创建的日记没有媒体项，但需要初始化关系以便Pydantic验证
        stmt = (
            select(Diary)
            .options(selectinload(Diary.media_items))
            .where(Diary.id == diary.id)
        )
        result = await self.db.execute(stmt)
        diary = result.scalar_one()
        decrypt_diary_fields(diary)
        
        # 🔥 自动触发提取任务（异步，不阻塞）
        self._trigger_extraction_async(diary.id, force=False)

        # 🔥 自动触发向量化任务（异步，不阻塞）
        self._trigger_embedding_async(diary.id, force=False)

        return diary
    
    async def get_diary_by_date(
        self, 
        user_id: int, 
        diary_date: date
    ) -> Optional[Diary]:
        """
        按日期查询日记（优化版）- 返回最新的一条
        
        使用joinedload进行预加载，减少数据库查询次数
        如果存在多个日记，返回最新创建的一个
        
        Args:
            user_id: 用户ID
            diary_date: 日记日期
            
        Returns:
            Optional[Diary]: 日记对象，如果不存在则返回None
            
        验证需求: 2.2, 7.5
        性能优化: 任务31 - 使用joinedload优化查询
        """
        # 构建查询，使用joinedload一次性加载媒体项（按sort_order排序）
        # 如果存在多个日记，按创建时间降序排列，取最新的一个
        stmt = (
            select(Diary)
            .options(
                joinedload(Diary.media_items)
            )
            .where(
                and_(
                    Diary.user_id == user_id,
                    Diary.diary_date == diary_date
                )
            )
            .order_by(Diary.created_at.desc())
            .limit(1)
        )
        
        result = await self.db.execute(stmt)
        diary = result.unique().scalar_one_or_none()
        
        # 如果找到日记，确保媒体项按sort_order排序
        if diary and diary.media_items:
            diary.media_items.sort(key=lambda x: x.sort_order)
        
        decrypt_diary_fields(diary)
        return diary
    
    async def get_diaries_by_date(
        self, 
        user_id: int, 
        diary_date: date
    ) -> List[Diary]:
        """
        按日期查询所有日记（支持多条）
        
        使用joinedload进行预加载，减少数据库查询次数
        返回该日期的所有日记，按创建时间降序排列
        
        Args:
            user_id: 用户ID
            diary_date: 日记日期
            
        Returns:
            List[Diary]: 日记列表，按创建时间降序排列
            
        验证需求: 2.2, 7.5
        """
        # 构建查询，使用joinedload一次性加载媒体项
        stmt = (
            select(Diary)
            .options(
                joinedload(Diary.media_items)
            )
            .where(
                and_(
                    Diary.user_id == user_id,
                    Diary.diary_date == diary_date
                )
            )
            .order_by(Diary.created_at.desc())
        )
        
        result = await self.db.execute(stmt)
        diaries = result.unique().scalars().all()
        
        # 确保每个日记的媒体项按sort_order排序
        for diary in diaries:
            if diary.media_items:
                diary.media_items.sort(key=lambda x: x.sort_order)
        
        return self._decrypt_diaries_list(diaries)
    
    async def get_diary_by_id(
        self,
        diary_id: int,
        load_media: bool = True
    ) -> Optional[Diary]:
        """
        按ID查询日记
        
        Args:
            diary_id: 日记ID
            load_media: 是否加载媒体项
            
        Returns:
            Optional[Diary]: 日记对象，如果不存在则返回None
            
        性能优化: 任务31 - 可选择性加载媒体项
        """
        stmt = select(Diary).where(Diary.id == diary_id)
        
        if load_media:
            stmt = stmt.options(joinedload(Diary.media_items))
        
        result = await self.db.execute(stmt)
        diary = result.unique().scalar_one_or_none() if load_media else result.scalar_one_or_none()
        
        if diary and load_media and diary.media_items:
            diary.media_items.sort(key=lambda x: x.sort_order)
        
        decrypt_diary_fields(diary)
        return diary
    
    async def update_diary(
        self, 
        diary_id: int, 
        data: UpdateDiaryRequest
    ) -> Optional[Diary]:
        """
        更新日记
        
        Args:
            diary_id: 日记ID
            data: 更新日记请求数据
            
        Returns:
            Optional[Diary]: 更新后的日记对象，如果不存在则返回None
            
        验证需求: 2.2, 2.4, 7.5, 1.1（检查内容变化并触发提取）
        """
        # 查询日记（加载媒体项和语音转录以计算哈希）
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
        
        if not diary:
            return None
        
        decrypt_diary_fields(diary)
        
        # 🔥 计算更新前的内容哈希（用于检测内容变化）
        old_hash = None
        if diary.content_hash:
            old_hash = diary.content_hash
        else:
            # 如果之前没有哈希，计算一个
            from app.services.extraction_service import ExtractionService
            extraction_service = ExtractionService(self.db)
            old_hash = extraction_service._calculate_content_hash(diary)
        
        # 更新字段（只更新非None的字段）
        update_data = data.model_dump(exclude_unset=True)
        content_changed = False
        
        for field, value in update_data.items():
            if field in ["title", "content"] and getattr(diary, field) != value:
                content_changed = True
            if field == "title":
                diary.title = encrypt_field(value) if value is not None else None
            elif field == "content":
                diary.content = encrypt_field(value) if value is not None else None
            else:
                setattr(diary, field, value)
        
        if data.content is not None:
            diary.word_count = len(data.content)
        
        await self.db.commit()
        
        # 预加载media_items关系以避免lazy loading错误
        stmt = (
            select(Diary)
            .options(
                selectinload(Diary.media_items),
                selectinload(Diary.voice_transcriptions)
            )
            .where(Diary.id == diary_id)
        )
        result = await self.db.execute(stmt)
        diary = result.scalar_one()
        
        # 确保媒体项按sort_order排序
        if diary.media_items:
            diary.media_items.sort(key=lambda x: x.sort_order)
        
        # 🔥 检查内容是否变化，如果变化则触发重新提取
        if content_changed:
            # 计算新的内容哈希
            from app.services.extraction_service import ExtractionService
            extraction_service = ExtractionService(self.db)
            new_hash = extraction_service._calculate_content_hash(diary)
            
            # 只有当旧哈希存在且与新哈希不同时，才触发提取
            # old_hash 为 None 表示从未提取过，跳过哈希比较
            if old_hash is not None and old_hash != new_hash:
                logger.info(f"检测到内容变化: diary_id={diary_id}, old_hash={old_hash[:8]}..., new_hash={new_hash[:8]}...")
                self._trigger_extraction_async(diary_id, force=False)
                self._trigger_embedding_async(diary_id, force=True)

        decrypt_diary_fields(diary)
        return diary
    
    async def get_dates_with_diary(
        self, 
        user_id: int, 
        year: int, 
        month: int
    ) -> List[str]:
        """
        获取有日记的日期列表（优化版）
        
        只查询日期字段，不加载完整的日记对象
        
        Args:
            user_id: 用户ID
            year: 年份
            month: 月份
            
        Returns:
            List[str]: 日期列表，格式为YYYY-MM-DD
            
        验证需求: 1.3, 7.5
        性能优化: 任务31 - 只查询需要的字段
        """
        # 构建查询：只选择日期字段，使用索引优化
        stmt = (
            select(Diary.diary_date)
            .where(
                and_(
                    Diary.user_id == user_id,
                    extract('year', Diary.diary_date) == year,
                    extract('month', Diary.diary_date) == month
                )
            )
            .order_by(Diary.diary_date)
            .distinct()  # 确保日期唯一
        )
        
        result = await self.db.execute(stmt)
        dates = result.scalars().all()
        
        # 转换为字符串格式
        return [d.strftime('%Y-%m-%d') for d in dates]
    
    async def get_diaries_by_date_range(
        self,
        user_id: int,
        start_date: date,
        end_date: date,
        limit: int = 50,
        offset: int = 0
    ) -> List[Diary]:
        """
        按日期范围查询日记（支持分页）
        
        Args:
            user_id: 用户ID
            start_date: 开始日期
            end_date: 结束日期
            limit: 每页数量
            offset: 偏移量
            
        Returns:
            List[Diary]: 日记列表
            
        性能优化: 任务31 - 支持分页加载
        """
        stmt = (
            select(Diary)
            .where(
                and_(
                    Diary.user_id == user_id,
                    Diary.diary_date >= start_date,
                    Diary.diary_date <= end_date
                )
            )
            .order_by(Diary.diary_date.desc())
            .limit(limit)
            .offset(offset)
        )
        
        result = await self.db.execute(stmt)
        return result.scalars().all()

    async def search_diaries_by_text(
        self,
        user_id: int,
        query: str,
        limit: int = 30,
        offset: int = 0
    ) -> List[Diary]:
        """
        Search diaries by keyword in title/content/text media.
        """
        normalized = query.strip()
        if not normalized:
            return []

        pattern = f"%{normalized}%"

        text_media_match = (
            select(DiaryMedia.id)
            .where(
                and_(
                    DiaryMedia.diary_id == Diary.id,
                    DiaryMedia.media_type == "text",
                    DiaryMedia.content.ilike(pattern),
                )
            )
            .exists()
        )

        stmt = (
            select(Diary)
            .options(joinedload(Diary.media_items))
            .where(
                and_(
                    Diary.user_id == user_id,
                    or_(
                        Diary.title.ilike(pattern),
                        Diary.content.ilike(pattern),
                        text_media_match,
                    ),
                )
            )
            .order_by(Diary.diary_date.desc(), Diary.created_at.desc())
            .limit(limit)
            .offset(offset)
        )

        result = await self.db.execute(stmt)
        diaries = list(result.unique().scalars().all())

        for diary in diaries:
            if diary.media_items:
                diary.media_items.sort(key=lambda item: item.sort_order)

        return self._decrypt_diaries_list(diaries)
    
    async def add_media_to_diary(
        self,
        diary_id: int,
        media_type: str,
        content: Optional[str] = None,
        media_url: Optional[str] = None,
        thumbnail_url: Optional[str] = None,
        duration: Optional[int] = None,
        file_size: Optional[int] = None
    ) -> DiaryMedia:
        """
        添加媒体到日记（优化版）
        
        自动计算sort_order（基于当前最大值+1）
        支持text、image、audio、video四种类型
        
        Args:
            diary_id: 日记ID
            media_type: 媒体类型(text/image/audio/video)
            content: 文本内容（仅用于text类型）
            media_url: 媒体文件URL
            thumbnail_url: 缩略图URL
            duration: 时长（秒，用于audio/video）
            file_size: 文件大小（字节）
            
        Returns:
            DiaryMedia: 创建的媒体对象
            
        验证需求: 2.6, 2.7, 7.7
        性能优化: 任务31 - 优化sort_order查询
        """
        # 验证媒体类型
        valid_types = ['text', 'image', 'audio', 'video']
        if media_type not in valid_types:
            raise ValueError(f"Invalid media_type. Must be one of: {valid_types}")
        
        # 查询当前日记的最大sort_order（使用索引优化）
        stmt = (
            select(func.coalesce(func.max(DiaryMedia.sort_order), -1))
            .where(DiaryMedia.diary_id == diary_id)
        )
        result = await self.db.execute(stmt)
        max_order = result.scalar()
        
        # 计算新的sort_order
        new_sort_order = max_order + 1
        
        # 创建媒体对象
        media = DiaryMedia(
            diary_id=diary_id,
            media_type=media_type,
            content=content,
            media_url=media_url,
            thumbnail_url=thumbnail_url,
            duration=duration,
            file_size=file_size,
            sort_order=new_sort_order
        )
        
        # 添加到数据库
        self.db.add(media)
        await self.db.commit()
        await self.db.refresh(media)
        
        return media

    async def get_highlight_diaries(
        self,
        user_id: int,
        limit: int = 20,
        offset: int = 0
    ) -> List[Diary]:
        """
        获取用户的高光时刻日记列表
        
        Args:
            user_id: 用户ID
            limit: 每页数量
            offset: 偏移量
            
        Returns:
            List[Diary]: 高光时刻日记列表
        """
        stmt = (
            select(Diary)
            .options(joinedload(Diary.media_items))
            .where(
                and_(
                    Diary.user_id == user_id,
                    Diary.is_highlight == 1
                )
            )
            .order_by(Diary.diary_date.desc())
            .limit(limit)
            .offset(offset)
        )
        
        result = await self.db.execute(stmt)
        diaries = result.unique().scalars().all()
        
        for diary in diaries:
            if diary.media_items:
                diary.media_items.sort(key=lambda x: x.sort_order)
        
        return self._decrypt_diaries_list(diaries)
    
    async def get_little_joy_diaries(
        self,
        user_id: int,
        limit: int = 20,
        offset: int = 0
    ) -> List[Diary]:
        """
        获取用户的小确幸日记列表
        
        Args:
            user_id: 用户ID
            limit: 每页数量
            offset: 偏移量
            
        Returns:
            List[Diary]: 小确幸日记列表
        """
        stmt = (
            select(Diary)
            .options(joinedload(Diary.media_items))
            .where(
                and_(
                    Diary.user_id == user_id,
                    Diary.is_little_joy == 1
                )
            )
            .order_by(Diary.diary_date.desc())
            .limit(limit)
            .offset(offset)
        )
        
        result = await self.db.execute(stmt)
        diaries = result.unique().scalars().all()
        
        for diary in diaries:
            if diary.media_items:
                diary.media_items.sort(key=lambda x: x.sort_order)
        
        return self._decrypt_diaries_list(diaries)

    async def sync_media_items(
        self,
        diary_id: int,
        items: List[SyncMediaItemRequest]
    ) -> List[DiaryMedia]:
        """
        批量同步媒体项（全量同步）
        
        逻辑：
        1. 查出该 diary 的所有 DiaryMedia
        2. 检查 sort_order 是否有重复
        3. 对比：
           - 有 id 且属于该 diary → UPDATE
           - 有 id 但不属于该 diary → 400 错误
           - id = null → INSERT
           - DB 有但请求没有 → DELETE
        4. 清理不再被引用的 media_uploads（任务 2, 4）
        5. 返回最新的 media_items（按 sort_order 排序）
        
        Args:
            diary_id: 日记ID
            items: 媒体项列表（全量）
            
        Returns:
            List[DiaryMedia]: 同步后的媒体项列表
            
        Raises:
            ValueError: sort_order 重复或 id 不属于该 diary
        """
        # 1️⃣ 查出该 diary 的所有现有 DiaryMedia
        stmt = select(DiaryMedia).where(DiaryMedia.diary_id == diary_id)
        result = await self.db.execute(stmt)
        existing_media = result.scalars().all()
        existing_map = {m.id: m for m in existing_media}
        
        # 2️⃣ 检查 sort_order 是否有重复（items 允许为空，用于删除全部）
        sort_orders = [item.sort_order for item in items]
        if len(sort_orders) != len(set(sort_orders)):
            raise ValueError("sort_order 不能重复")
        
        request_ids = set()
        # 收集请求中使用的 asset_id（用于后续清理判断）
        request_asset_ids = set()
        # 收集被删除的 diary_media 的 asset_id（用于清理）
        deleted_asset_ids = set()
        
        # 3️⃣ 遍历请求，执行 INSERT/UPDATE
        for item in items:
            # 收集请求中的 asset_id
            if item.asset_id is not None:
                request_asset_ids.add(item.asset_id)
            
            if item.id is not None:
                # ✅ 校验：id 必须属于该 diary（否则 400）
                media = existing_map.get(item.id)
                if media is None:
                    raise ValueError(f"media_id {item.id} 不存在或不属于该日记 (diary_id={diary_id})")
                
                # 如果 asset_id 发生变化，记录旧的 asset_id 用于清理
                if media.asset_id is not None and media.asset_id != item.asset_id:
                    deleted_asset_ids.add(media.asset_id)
                
                media.asset_id = item.asset_id
                media.media_type = item.media_type
                media.content = item.content
                media.media_url = item.media_url
                media.thumbnail_url = item.thumbnail_url
                media.duration = item.duration
                media.file_size = item.file_size
                media.sort_order = item.sort_order
                
                request_ids.add(item.id)
            else:
                new_media = DiaryMedia(
                    diary_id=diary_id,
                    asset_id=item.asset_id,
                    media_type=item.media_type,
                    content=item.content,
                    media_url=item.media_url,
                    thumbnail_url=item.thumbnail_url,
                    duration=item.duration,
                    file_size=item.file_size,
                    sort_order=item.sort_order
                )
                self.db.add(new_media)
        
        # 4️⃣ 删除 DB 中存在但请求中没有的项（items 为空时会删光）
        for media_id, media in existing_map.items():
            if media_id not in request_ids:
                # 记录被删除的 asset_id
                if media.asset_id is not None:
                    deleted_asset_ids.add(media.asset_id)
                await self.db.delete(media)
        
        # 5️⃣ flush 确保新插入的记录获得真实 ID
        await self.db.flush()
        
        # 6️⃣ 清理不再被引用的 media_uploads（任务 2, 4）
        # 从 deleted_asset_ids 中移除仍在使用的 asset_id
        orphan_asset_ids = deleted_asset_ids - request_asset_ids
        
        if orphan_asset_ids:
            # 检查这些 asset_id 是否被其他 diary_media 引用
            from app.models.database import MediaUpload
            
            for asset_id in orphan_asset_ids:
                # 查询是否有其他 diary_media 引用此 asset_id
                check_stmt = select(func.count()).where(DiaryMedia.asset_id == asset_id)
                result = await self.db.execute(check_stmt)
                ref_count = result.scalar()
                
                if ref_count == 0:
                    # 没有其他引用，可以删除 media_upload 记录
                    # 注意：这里只删除数据库记录，实际文件删除可以通过定时任务处理
                    delete_stmt = select(MediaUpload).where(MediaUpload.id == asset_id)
                    result = await self.db.execute(delete_stmt)
                    media_upload = result.scalar_one_or_none()
                    if media_upload:
                        await self.db.delete(media_upload)
        
        await self.db.commit()
        
        # 7️⃣ 重新查询并返回最新的 media_items（按 sort_order 排序）
        stmt = (
            select(DiaryMedia)
            .where(DiaryMedia.diary_id == diary_id)
            .order_by(DiaryMedia.sort_order)
        )
        result = await self.db.execute(stmt)
        return list(result.scalars().all())

    async def delete_diary(self, diary_id: int, user_id: int) -> bool:
        """Delete a diary without touching optional legacy ORM backrefs."""

        from app.models.database import MediaUpload, VoiceTranscription

        try:
            result = await self.db.execute(select(Diary).where(Diary.id == diary_id))
            diary = result.scalar_one_or_none()
            if not diary or diary.user_id != user_id:
                return False

            media_result = await self.db.execute(
                select(DiaryMedia.id, DiaryMedia.asset_id).where(DiaryMedia.diary_id == diary_id)
            )
            media_rows = media_result.all()
            media_ids = [row.id for row in media_rows]
            asset_ids_to_check = {row.asset_id for row in media_rows if row.asset_id is not None}

            await self.db.execute(delete(VoiceTranscription).where(VoiceTranscription.diary_id == diary_id))

            if media_ids:
                await self.db.execute(delete(DiaryMedia).where(DiaryMedia.id.in_(media_ids)))

            delete_result = await self.db.execute(
                delete(Diary).where(Diary.id == diary_id, Diary.user_id == user_id)
            )
            if not delete_result.rowcount:
                await self.db.rollback()
                return False

            for asset_id in asset_ids_to_check:
                check_result = await self.db.execute(
                    select(func.count()).where(DiaryMedia.asset_id == asset_id)
                )
                if check_result.scalar() != 0:
                    continue

                upload_result = await self.db.execute(select(MediaUpload).where(MediaUpload.id == asset_id))
                media_upload = upload_result.scalar_one_or_none()
                if media_upload:
                    await self.db.delete(media_upload)

            await self.db.commit()

            # 🔥 从 FAISS 索引中移除日记的向量
            self._remove_embedding_from_faiss(diary_id, user_id)

            return True
        except Exception:
            await self.db.rollback()
            raise

    def _remove_embedding_from_faiss(self, diary_id: int, user_id: int) -> None:
        """Remove a diary's embedding from FAISS index (non-blocking)."""
        try:
            from app.services.diary_embedding_service import DiaryEmbeddingService

            # FAISS store operations are synchronous but fast; run in thread pool
            asyncio.create_task(
                asyncio.to_thread(
                    DiaryEmbeddingService.remove_embedding_sync,
                    diary_id,
                    user_id,
                )
            )
        except Exception as e:
            logger.error(f"Failed to remove embedding from FAISS: diary_id={diary_id}, error={str(e)}")

    async def get_diary_media_items(self, diary_id: int) -> List:
        """Return aggregated diary media while tolerating missing legacy tables."""

        from app.models.database import MediaFile, MediaUpload
        from app.models.schemas import MediaItemAggregatedResponse
        from app.services.media_service import MediaService
        from app.security.media_access import resolve_media_url

        media_items = []

        if await self._media_files_table_exists():
            try:
                oss_result = await self.db.execute(select(MediaFile).where(MediaFile.diary_id == diary_id))
                oss_media = oss_result.scalars().all()

                for media in oss_media:
                    media_items.append(
                        MediaItemAggregatedResponse(
                            media_id=media.id,
                            type=media.type,
                            url=resolve_media_url(media.url) or media.url,
                            thumbnail_url=None,
                            size_bytes=media.size_bytes or 0,
                            duration_ms=media.duration_ms,
                            content_type=media.content_type or "",
                            storage_mode="oss",
                            created_at=media.created_at.isoformat(),
                        )
                    )
            except DBAPIError as exc:
                if not self._can_skip_media_files_error(exc):
                    raise

                logger.warning("Skipping media_files aggregation for diary_id=%s: %s", diary_id, exc)
                self._media_files_table_available = False
                await self.db.rollback()

        local_result = await self.db.execute(
            select(DiaryMedia)
            .where(DiaryMedia.diary_id == diary_id)
            .where(DiaryMedia.asset_id.isnot(None))
        )
        diary_media_items = local_result.scalars().all()
        asset_ids = [item.asset_id for item in diary_media_items if item.asset_id]

        if asset_ids:
            upload_result = await self.db.execute(select(MediaUpload).where(MediaUpload.id.in_(asset_ids)))
            uploads = upload_result.scalars().all()
            upload_map = {upload.id: upload for upload in uploads}

            media_service = MediaService()
            for diary_media in diary_media_items:
                upload = upload_map.get(diary_media.asset_id)
                if not upload:
                    continue

                url = media_service.get_file_url(upload.file_path)
                url = resolve_media_url(url) or url
                thumbnail_url = (
                    resolve_media_url(media_service.get_file_url(upload.thumbnail_path))
                    if upload.thumbnail_path
                    else None
                )
                storage_mode = "oss" if url.startswith(("http://", "https://")) else "local"

                media_items.append(
                    MediaItemAggregatedResponse(
                        media_id=upload.id,
                        type=upload.file_type,
                        url=url,
                        thumbnail_url=thumbnail_url,
                        size_bytes=upload.file_size or 0,
                        duration_ms=upload.duration,
                        content_type=upload.mime_type or "",
                        storage_mode=storage_mode,
                        created_at=upload.created_at.isoformat(),
                    )
                )

        media_items.sort(key=lambda item: item.created_at)
        return media_items
