"""
SQLAlchemy数据库模型
严格对应init_database.sql中的表结构
"""
from sqlalchemy import (
    Column, BigInteger, String, Text, Date, Integer, 
    SmallInteger, TIMESTAMP, ForeignKey, DECIMAL, CheckConstraint, ARRAY
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import backref, declarative_base, relationship
from datetime import datetime

Base = declarative_base()


class User(Base):
    """用户表"""
    __tablename__ = "users"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    username = Column(String(50), unique=True, nullable=False)
    password = Column(String(255), nullable=False)
    nickname = Column(String(50))
    avatar = Column(String(500))
    phone = Column(String(20), unique=True)
    email = Column(String(100), unique=True)
    gender = Column(SmallInteger, default=0)  # 0未知/1男/2女
    birthday = Column(Date)
    status = Column(SmallInteger, default=1)  # 0禁用/1正常
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)
    
    # Relationships
    diaries = relationship("Diary", back_populates="user", cascade="all, delete-orphan")
    todos = relationship("Todo", back_populates="user", cascade="all, delete-orphan")
    agent_plans = relationship("ChatAgentPlan", back_populates="user", cascade="all, delete-orphan")


class Diary(Base):
    """日记表"""
    __tablename__ = "diaries"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    title = Column(String(200))
    content = Column(Text)
    diary_date = Column(Date, nullable=False)
    weather = Column(String(50))
    location = Column(String(200))
    mood_score = Column(Integer, CheckConstraint("mood_score >= 1 AND mood_score <= 100"))
    mood_type = Column(String(50))
    is_private = Column(SmallInteger, default=1)  # 0公开/1私密
    is_extracted = Column(SmallInteger, default=0)  # 0否/1是（向后兼容）
    is_highlight = Column(SmallInteger, default=0)  # 0否/1是 高光时刻
    is_little_joy = Column(SmallInteger, default=0)  # 0否/1是 小确幸
    word_count = Column(Integer, default=0)
    
    # V1 提取相关字段
    extraction_status = Column(String(20), default="pending")  # pending/processing/succeeded/failed
    content_hash = Column(String(64))  # SHA256 哈希值，用于幂等性检查
    extract_version = Column(Integer, default=1)  # 提取算法版本号

    # 向量化相关字段
    embedding_status = Column(String(20), default="pending")  # pending/processing/succeeded/failed
    
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)
    
    # Relationships
    user = relationship("User", back_populates="diaries")
    media_items = relationship("DiaryMedia", back_populates="diary", cascade="all, delete-orphan")
    voice_transcriptions = relationship("VoiceTranscription", back_populates="diary", cascade="all, delete-orphan")


class Todo(Base):
    """Todo table."""
    __tablename__ = "todos"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    todo_date = Column(Date, nullable=False)
    title = Column(String(200), nullable=False)
    note = Column(Text)
    is_done = Column(SmallInteger, default=0)  # 0 undone / 1 done
    sort_order = Column(Integer, default=0)
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)

    # Relationships
    user = relationship("User", back_populates="todos")


class ChatAgentPlan(Base):
    """Pending/confirmed plan records for chat agent tool operations."""
    __tablename__ = "chat_agent_plans"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    plan_id = Column(String(64), unique=True, nullable=False)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    conversation_id = Column(String(128), nullable=False)
    tool = Column(String(64), nullable=False)
    mode = Column(String(20), nullable=False)  # read_only/operation/client_action
    requires_confirmation = Column(SmallInteger, default=1)  # 0/1
    arguments = Column(JSONB, nullable=False)
    request_message = Column(Text)
    context_snapshot = Column(Text)
    status = Column(String(20), default="pending")  # pending/cancelled/executed/failed/expired
    error_code = Column(String(64))
    error_message = Column(Text)
    confirmed_at = Column(TIMESTAMP)
    executed_at = Column(TIMESTAMP)
    created_at = Column(TIMESTAMP, default=datetime.now)
    expires_at = Column(TIMESTAMP, nullable=False)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)

    # Relationships
    user = relationship("User", back_populates="agent_plans")


class DiaryMedia(Base):
    """日记媒体表（日记内容块）"""
    __tablename__ = "diary_media"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="CASCADE"), nullable=False)
    asset_id = Column(BigInteger, ForeignKey("media_files.id", ondelete="SET NULL"), nullable=True)
    media_type = Column(String(20), nullable=False)  # text/image/video/audio
    content = Column(Text)
    media_url = Column(String(500))
    thumbnail_url = Column(String(500))
    duration = Column(Integer)
    file_size = Column(BigInteger)
    sort_order = Column(Integer, default=0)
    created_at = Column(TIMESTAMP, default=datetime.now)
    
    # Relationships
    diary = relationship("Diary", back_populates="media_items")
    voice_transcription = relationship("VoiceTranscription", back_populates="media", uselist=False)
    asset = relationship("MediaFile", back_populates="diary_media_items")


class VoiceTranscription(Base):
    """语音转文字记录表"""
    __tablename__ = "voice_transcriptions"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="CASCADE"), nullable=False)
    media_id = Column(BigInteger, ForeignKey("diary_media.id", ondelete="CASCADE"))
    original_text = Column(Text)
    processed_text = Column(Text)
    confidence = Column(DECIMAL(5, 2))
    detected_emotion = Column(String(50))
    emotion_score = Column(DECIMAL(5, 2))
    created_at = Column(TIMESTAMP, default=datetime.now)
    
    # Relationships
    diary = relationship("Diary", back_populates="voice_transcriptions")
    media = relationship("DiaryMedia", back_populates="voice_transcription")


class MediaUpload(Base):
    """媒体上传表（存储上传的文件资源）"""
    __tablename__ = "media_uploads"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    file_name = Column(String(255), nullable=False)
    file_path = Column(String(500), nullable=False)
    file_type = Column(String(50), nullable=False)  # image/video/audio
    file_size = Column(BigInteger, nullable=False)
    mime_type = Column(String(100))
    thumbnail_path = Column(String(500))
    duration = Column(Integer)
    width = Column(Integer)
    height = Column(Integer)
    checksum = Column(String(64))
    created_at = Column(TIMESTAMP, default=datetime.now)
    
    # Relationships
    user = relationship("User")


class MediaFile(Base):
    """媒体文件表（OSS 存储）"""
    __tablename__ = "media_files"
    
    id = Column(BigInteger, primary_key=True)  # BIGSERIAL 由数据库处理
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="SET NULL"), nullable=True)
    type = Column(String(16), nullable=False)  # image/audio/video
    url = Column(Text, nullable=False)
    oss_bucket = Column(Text)
    oss_key = Column(Text)
    content_type = Column(Text)
    size_bytes = Column(BigInteger)
    duration_ms = Column(Integer)
    created_at = Column(TIMESTAMP, default=datetime.now)
    
    # Relationships
    diary = relationship("Diary", backref=backref("media_files", passive_deletes=True))
    diary_media_items = relationship("DiaryMedia", back_populates="asset")


class EmotionRecord(Base):
    """情绪记录表"""
    __tablename__ = "emotion_records"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    source_type = Column(String(50), nullable=False)  # diary/voice/chat
    source_id = Column(BigInteger)
    emotion_type = Column(String(50), nullable=False)
    emotion_score = Column(Integer, CheckConstraint("emotion_score >= 1 AND emotion_score <= 100"))
    analysis = Column(Text)
    recorded_at = Column(TIMESTAMP, nullable=False)
    created_at = Column(TIMESTAMP, default=datetime.now)
    
    # Relationships
    user = relationship("User")


class ExtractionJob(Base):
    """提取任务记录表"""
    __tablename__ = "extraction_jobs"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="CASCADE"), nullable=False)
    status = Column(String(20), nullable=False)  # pending/processing/succeeded/failed
    attempts = Column(Integer, default=0)
    error_message = Column(Text)
    error_code = Column(String(50))
    started_at = Column(TIMESTAMP)
    completed_at = Column(TIMESTAMP)
    execution_time_ms = Column(Integer)
    llm_tokens_used = Column(Integer)
    content_hash = Column(String(64))
    extract_version = Column(Integer)
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)
    
    # Relationships
    diary = relationship("Diary", backref="extraction_jobs")


class DiarySummary(Base):
    """日记摘要表"""
    __tablename__ = "diary_summaries"
    
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="CASCADE"), unique=True, nullable=False)
    
    # 摘要信息
    summary = Column(Text)
    keywords = Column(ARRAY(Text))  # TEXT[] 数组
    main_topics = Column(ARRAY(Text))  # TEXT[] 数组
    
    # 人物和地点
    people_mentioned = Column(JSONB)
    places_mentioned = Column(JSONB)
    
    # 情绪分析（独立字段，便于查询）
    primary_emotion = Column(String(20))  # 主要情绪（枚举）
    emotion_score = Column(Integer)  # 情绪评分（1-100）
    emotion_intensity = Column(String(20))  # 情绪强度（轻微/中等/强烈）
    emotion_distribution = Column(JSONB)  # 情绪分布
    emotion_analysis = Column(JSONB)  # 完整的情绪分析结果（保留用于详细信息）
    
    # 其他分析
    chat_analysis = Column(JSONB)
    test_analysis = Column(JSONB)
    artwork_analysis = Column(JSONB)
    
    # 高光时刻和小确幸
    has_small_happiness = Column(SmallInteger, default=0)
    small_happiness_content = Column(Text)
    has_highlight = Column(SmallInteger, default=0)
    highlight_summary = Column(Text)
    
    # 元数据
    extract_version = Column(Integer, default=1)
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)
    
    # Relationships
    diary = relationship("Diary", backref="summary", uselist=False)


class DiaryMultimodalInsight(Base):
    """Per-diary multimodal insight record."""
    __tablename__ = "diary_multimodal_insights"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    diary_id = Column(BigInteger, ForeignKey("diaries.id", ondelete="CASCADE"), unique=True, nullable=False)
    text_analysis = Column(JSONB)
    voice_analysis = Column(JSONB)
    image_analysis = Column(JSONB)
    video_analysis = Column(JSONB)
    drawing_analysis = Column(JSONB)
    fusion_analysis = Column(JSONB)
    stress_score = Column(Integer, CheckConstraint("stress_score >= 0 AND stress_score <= 100"))
    positive_event_score = Column(Integer, CheckConstraint("positive_event_score >= 0 AND positive_event_score <= 100"))
    confidence_score = Column(Integer, CheckConstraint("confidence_score >= 0 AND confidence_score <= 100"))
    analysis_version = Column(Integer, default=1)
    created_at = Column(TIMESTAMP, default=datetime.now)
    updated_at = Column(TIMESTAMP, default=datetime.now, onupdate=datetime.now)

    diary = relationship("Diary", backref=backref("multimodal_insight", uselist=False), uselist=False)


class SecurityAuditLog(Base):
    """安全审计日志"""
    __tablename__ = "security_audit_logs"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger, ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    action = Column(String(64), nullable=False)
    resource_type = Column(String(64), nullable=False)
    resource_id = Column(String(128))
    result = Column(String(32), default="success")
    ip_address = Column(String(64))
    detail = Column(Text)
    created_at = Column(TIMESTAMP, default=datetime.now)
