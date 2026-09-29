"""
Pydantic请求和响应模型
用于API数据验证和序列化
"""
from pydantic import BaseModel, Field, ConfigDict, field_validator
from datetime import date as date_type, datetime
from typing import Optional, List, Dict, Any
from decimal import Decimal
from enum import Enum


# ============================================================================
# 请求模型 (Request Models)
# ============================================================================


def _validate_diary_date_not_in_future(value: date_type) -> date_type:
    """Reject diary dates later than today."""
    if value > date_type.today():
        raise ValueError("日记日期不能晚于今天")
    return value

class CreateDiaryRequest(BaseModel):
    """创建日记请求"""
    user_id: int = Field(..., description="用户ID")
    title: Optional[str] = Field(None, max_length=200, description="日记标题")
    content: Optional[str] = Field(None, description="日记内容")
    diary_date: date_type = Field(..., description="日记日期")
    weather: Optional[str] = Field(None, max_length=50, description="天气")
    location: Optional[str] = Field(None, max_length=200, description="地点")
    mood_score: Optional[int] = Field(None, ge=1, le=100, description="心情评分(1-100)")
    mood_type: Optional[str] = Field(None, max_length=50, description="心情类型")
    is_private: Optional[int] = Field(1, description="是否私密(0公开/1私密)")

    @field_validator("diary_date")
    @classmethod
    def validate_diary_date(cls, value: date_type) -> date_type:
        return _validate_diary_date_not_in_future(value)


class UpdateDiaryRequest(BaseModel):
    """更新日记请求"""
    title: Optional[str] = Field(None, max_length=200, description="日记标题")
    content: Optional[str] = Field(None, description="日记内容")
    weather: Optional[str] = Field(None, max_length=50, description="天气")
    location: Optional[str] = Field(None, max_length=200, description="地点")
    mood_score: Optional[int] = Field(None, ge=1, le=100, description="心情评分(1-100)")
    mood_type: Optional[str] = Field(None, max_length=50, description="心情类型")
    is_private: Optional[int] = Field(None, description="是否私密(0公开/1私密)")
    is_highlight: Optional[int] = Field(None, description="是否高光时刻(0否/1是)")
    is_little_joy: Optional[int] = Field(None, description="是否小确幸(0否/1是)")


class AddMediaRequest(BaseModel):
    """添加媒体请求"""
    diary_id: int = Field(..., description="日记ID")
    media_type: str = Field(..., description="媒体类型(text/image/audio/video)")
    content: Optional[str] = Field(None, description="文本内容")
    media_url: Optional[str] = Field(None, max_length=500, description="媒体URL")
    thumbnail_url: Optional[str] = Field(None, max_length=500, description="缩略图URL")
    duration: Optional[int] = Field(None, description="时长(秒)")
    file_size: Optional[int] = Field(None, description="文件大小(字节)")
    sort_order: int = Field(..., description="排序顺序")


class SyncMediaItemRequest(BaseModel):
    """同步媒体项请求（单个）"""
    id: Optional[int] = Field(None, description="媒体ID（BlockId），null表示新增")
    asset_id: Optional[int] = Field(None, description="关联的上传资源ID（AssetId），可选")
    media_type: str = Field(..., description="媒体类型(text/image/audio/video)")
    content: Optional[str] = Field(None, description="文本内容（text类型）")
    media_url: Optional[str] = Field(None, max_length=500, description="媒体URL")
    thumbnail_url: Optional[str] = Field(None, max_length=500, description="缩略图URL")
    duration: Optional[int] = Field(None, description="时长(秒)")
    file_size: Optional[int] = Field(None, description="文件大小(字节)")
    sort_order: int = Field(..., ge=0, description="排序顺序")


class SyncMediaRequest(BaseModel):
    """批量同步媒体请求（全量同步）"""
    items: List[SyncMediaItemRequest] = Field(default_factory=list, description="媒体项列表（全量同步）")


# ============================================================================
# 响应模型 (Response Models)
# ============================================================================

class MediaItemResponse(BaseModel):
    """媒体项响应"""
    id: int = Field(..., description="媒体ID（BlockId，日记内容块的ID）")
    diary_id: int = Field(..., description="日记ID")
    asset_id: Optional[int] = Field(None, description="关联的上传资源ID（AssetId）")
    media_type: str = Field(..., description="媒体类型(text/image/audio/video)")
    content: Optional[str] = Field(None, description="文本内容")
    media_url: Optional[str] = Field(None, description="媒体URL")
    thumbnail_url: Optional[str] = Field(None, description="缩略图URL")
    duration: Optional[int] = Field(None, description="时长(秒)")
    file_size: Optional[int] = Field(None, description="文件大小(字节)")
    sort_order: int = Field(..., description="排序顺序")
    created_at: datetime = Field(..., description="创建时间")
    
    model_config = ConfigDict(from_attributes=True)


class VoiceTranscriptionResponse(BaseModel):
    """语音转录响应"""
    id: int = Field(..., description="转录记录ID")
    diary_id: int = Field(..., description="日记ID")
    media_id: Optional[int] = Field(None, description="媒体ID")
    original_text: Optional[str] = Field(None, description="原始转录文本")
    processed_text: Optional[str] = Field(None, description="处理后的文本")
    confidence: Optional[Decimal] = Field(None, description="转录置信度")
    detected_emotion: Optional[str] = Field(None, description="检测到的情感")
    emotion_score: Optional[Decimal] = Field(None, description="情感评分")
    created_at: datetime = Field(..., description="创建时间")
    
    model_config = ConfigDict(from_attributes=True)


class MediaItemAggregatedResponse(BaseModel):
    """
    统一的媒体项响应（聚合 OSS 和本地存储）
    
    用于日记详情查询，聚合 media_files 和 media_uploads 两张表的数据
    """
    media_id: int = Field(..., description="媒体ID")
    type: str = Field(..., description="媒体类型（image/audio/video）")
    url: str = Field(..., description="访问URL")
    thumbnail_url: Optional[str] = Field(None, description="缩略图URL")
    size_bytes: Optional[int] = Field(0, description="文件大小(字节)")
    duration_ms: Optional[int] = Field(None, description="时长(毫秒)")
    content_type: str = Field(..., description="MIME 类型")
    storage_mode: str = Field(..., description="存储模式（oss/local）")
    created_at: str = Field(..., description="创建时间（ISO 格式）")
    
    model_config = ConfigDict(from_attributes=True)


class DiaryResponse(BaseModel):
    """日记响应"""
    id: int = Field(..., description="日记ID")
    user_id: int = Field(..., description="用户ID")
    title: Optional[str] = Field(None, description="日记标题")
    content: Optional[str] = Field(None, description="日记内容")
    diary_date: date_type = Field(..., description="日记日期")
    weather: Optional[str] = Field(None, description="天气")
    location: Optional[str] = Field(None, description="地点")
    mood_score: Optional[int] = Field(None, description="心情评分")
    mood_type: Optional[str] = Field(None, description="心情类型")
    is_private: int = Field(..., description="是否私密")
    is_extracted: int = Field(..., description="是否已提取")
    is_highlight: int = Field(0, description="是否高光时刻")
    is_little_joy: int = Field(0, description="是否小确幸")
    word_count: int = Field(..., description="字数")
    # 提取相关字段
    extraction_status: Optional[str] = Field(None, description="提取状态: pending/processing/succeeded/failed")
    content_hash: Optional[str] = Field(None, description="内容哈希值")
    extract_version: Optional[int] = Field(None, description="提取算法版本")
    # 向量化相关字段
    embedding_status: Optional[str] = Field(None, description="向量化状态: pending/processing/succeeded/failed")
    created_at: datetime = Field(..., description="创建时间")
    updated_at: datetime = Field(..., description="更新时间")
    media_items: List[MediaItemResponse] = Field(default_factory=list, description="媒体项列表")
    
    model_config = ConfigDict(from_attributes=True)


class DiaryDetailResponse(BaseModel):
    """
    日记详情响应（包含聚合的媒体列表）
    
    用于返回日记详情，包含从 media_files 和 media_uploads 聚合的媒体列表
    """
    id: int = Field(..., description="日记ID")
    user_id: int = Field(..., description="用户ID")
    title: Optional[str] = Field(None, description="日记标题")
    content: Optional[str] = Field(None, description="日记内容")
    diary_date: date_type = Field(..., description="日记日期")
    weather: Optional[str] = Field(None, description="天气")
    location: Optional[str] = Field(None, description="地点")
    mood_score: Optional[int] = Field(None, description="心情评分")
    mood_type: Optional[str] = Field(None, description="心情类型")
    is_private: int = Field(..., description="是否私密")
    is_extracted: int = Field(..., description="是否已提取")
    is_highlight: int = Field(0, description="是否高光时刻")
    is_little_joy: int = Field(0, description="是否小确幸")
    word_count: int = Field(..., description="字数")
    # 提取相关字段
    extraction_status: Optional[str] = Field(None, description="提取状态: pending/processing/succeeded/failed")
    content_hash: Optional[str] = Field(None, description="内容哈希值")
    extract_version: Optional[int] = Field(None, description="提取算法版本")
    # 向量化相关字段
    embedding_status: Optional[str] = Field(None, description="向量化状态: pending/processing/succeeded/failed")
    created_at: datetime = Field(..., description="创建时间")
    updated_at: datetime = Field(..., description="更新时间")
    media_items_aggregated: List[MediaItemAggregatedResponse] = Field(default_factory=list, description="聚合的媒体列表（OSS + 本地）")
    
    model_config = ConfigDict(from_attributes=True)


class EmotionResult(BaseModel):
    """情感分析结果"""
    emotion_type: str = Field(..., description="情感类型(如：快乐、悲伤、愤怒、中性等)")
    score: int = Field(..., ge=1, le=100, description="情感强度评分(1-100)")
    confidence: float = Field(..., ge=0.0, le=1.0, description="置信度(0-1)")
    details: Optional[dict] = Field(None, description="详细信息")


class VoiceProcessingResult(BaseModel):
    """语音处理结果"""
    original_text: str = Field(..., description="原始转录文本")
    processed_text: str = Field(..., description="处理后的文本(去除冗余词)")
    emotion: EmotionResult = Field(..., description="情感分析结果")
    request_id: str = Field(..., description="请求ID")
    timestamp: datetime = Field(..., description="处理时间戳")


class VoiceProcessWithOptionsResponse(BaseModel):
    """
    语音处理（带选项）响应
    
    根据 save_mode 返回不同的处理结果：
    - save_mode=1: 仅保存音频 + 语音情感分析
    - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（创建文本媒体项）
    - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析（创建文本媒体项）
    
    状态说明：
    - status="success": 处理完成
    - status="processing": ASR 仍在处理中，需要前端轮询 /voice/asr-status
    - status="failure": 处理失败
    
    需求: 1.2, 1.3, 1.4, 2.1, 3.1, 4.1, 4.2, 4.3, 4.4
    """
    success: bool = Field(..., description="处理是否成功（兼容旧版）")
    status: Optional[str] = Field("success", description="状态: success | processing | failure")
    task_id: Optional[str] = Field(None, description="ASR 任务ID（用于查询状态）")
    media_id: Optional[int] = Field(None, description="音频媒体ID（save_mode=1或3时）")
    media_url: Optional[str] = Field(None, description="音频媒体URL（save_mode=1或3时）")
    text_media_id: Optional[int] = Field(None, description="文本媒体ID（save_mode=2或3时）")
    transcription_id: Optional[int] = Field(None, description="转录记录ID")
    original_text: Optional[str] = Field(None, description="原始转录文本")
    processed_text: Optional[str] = Field(None, description="处理后的流畅文本")
    emotion: Optional[EmotionResult] = Field(None, description="情感分析结果")
    error: Optional[str] = Field(None, description="错误信息（如果有）")
    message: Optional[str] = Field(None, description="状态消息")
    asr_status: Optional[str] = Field(None, description="百度 ASR 原始状态")


class AsrStatusResponse(BaseModel):
    """
    ASR 状态查询响应
    
    用于前端轮询 ASR 任务状态
    """
    status: str = Field(..., description="状态: success | processing | failure")
    task_id: str = Field(..., description="ASR 任务ID")
    text: Optional[str] = Field(None, description="转写文本（success 时）")
    error_code: Optional[int] = Field(None, description="错误码（failure 时）")
    error_msg: Optional[str] = Field(None, description="错误信息（failure 时）")
    asr_status: Optional[str] = Field(None, description="百度 ASR 原始状态")


class MediaUploadResponse(BaseModel):
    """
    媒体上传响应
    
    用于 /media/upload 端点的响应，支持 OSS 和本地存储两种模式
    """
    media_id: int = Field(..., description="媒体ID（asset_id）")
    media_url: str = Field(..., description="媒体访问URL（OSS 或本地）")
    thumbnail_url: Optional[str] = Field(None, description="缩略图URL")
    file_size: int = Field(..., description="文件大小(字节)")
    type: Optional[str] = Field(None, description="媒体类型（image/audio/video）")
    duration_ms: Optional[int] = Field(None, description="时长(毫秒，仅音频/视频)")
    content_type: Optional[str] = Field(None, description="MIME 类型")
    storage_mode: Optional[str] = Field(None, description="存储模式（oss/local）")
    message: str = Field(default="文件上传成功", description="响应消息")


class DatesWithDiaryResponse(BaseModel):
    """有日记的日期列表响应"""
    dates: List[str] = Field(..., description="日期列表(YYYY-MM-DD格式)")
    year: int = Field(..., description="年份")
    month: int = Field(..., description="月份")
    count: int = Field(..., description="日记数量")


class ErrorResponse(BaseModel):
    """错误响应"""
    error: str = Field(..., description="错误类型")
    detail: Optional[str] = Field(None, description="错误详情")
    timestamp: datetime = Field(default_factory=datetime.now, description="错误时间戳")
    request_id: Optional[str] = Field(None, description="请求ID")


# ============================================================================
# 其他辅助模型
# ============================================================================

class SuccessResponse(BaseModel):
    """通用成功响应"""
    success: bool = Field(True, description="操作是否成功")
    message: str = Field(..., description="响应消息")
    data: Optional[dict] = Field(None, description="附加数据")


# ============================================================================
# 用户相关模型 (User Models)
# ============================================================================

class LoginRequest(BaseModel):
    """用户登录请求"""
    username: str = Field(..., min_length=1, max_length=50, description="用户名")
    password: str = Field(..., min_length=6, description="密码")


class RegisterRequest(BaseModel):
    """用户注册请求"""
    username: str = Field(..., min_length=1, max_length=50, description="用户名")
    password: str = Field(..., min_length=6, description="密码")
    nickname: Optional[str] = Field(None, max_length=50, description="昵称")


class UpdateUserRequest(BaseModel):
    """更新用户信息请求"""
    nickname: Optional[str] = Field(None, max_length=50, description="昵称")
    phone: Optional[str] = Field(None, max_length=20, description="手机号")
    email: Optional[str] = Field(None, max_length=100, description="邮箱")
    gender: Optional[int] = Field(None, ge=0, le=2, description="性别(0未知/1男/2女)")
    birthday: Optional[date_type] = Field(None, description="生日")


class UserResponse(BaseModel):
    """用户信息响应"""
    id: int = Field(..., description="用户ID")
    username: str = Field(..., description="用户名")
    nickname: Optional[str] = Field(None, description="昵称")
    avatar: Optional[str] = Field(None, description="头像URL")
    phone: Optional[str] = Field(None, description="手机号")
    email: Optional[str] = Field(None, description="邮箱")
    gender: int = Field(..., description="性别(0未知/1男/2女)")
    birthday: Optional[date_type] = Field(None, description="生日")
    status: int = Field(..., description="状态(0禁用/1正常)")
    created_at: datetime = Field(..., description="创建时间")
    updated_at: datetime = Field(..., description="更新时间")
    
    model_config = ConfigDict(from_attributes=True)


class AuthResponse(UserResponse):
    """登录/注册响应（含访问令牌）"""
    access_token: str = Field(..., description="JWT 访问令牌")
    token_type: str = Field(default="bearer", description="令牌类型")


class AvatarUploadResponse(BaseModel):
    """头像上传响应"""
    avatar_url: str = Field(..., description="头像访问URL")
    message: str = Field(default="头像上传成功", description="响应消息")


# ============================================================================
# 日记提取相关模型 (Diary Extraction Models)
# ============================================================================

class EmotionType(str, Enum):
    """情绪类型枚举"""
    HAPPY = "开心"
    CALM = "平静"
    ANXIOUS = "焦虑"
    ANGRY = "愤怒"
    SAD = "低落"
    EXCITED = "兴奋"
    COMPLEX = "复杂"
    NEUTRAL = "中性"


class EmotionIntensity(str, Enum):
    """情绪强度枚举"""
    MILD = "轻微"
    MODERATE = "中等"
    STRONG = "强烈"


class EmotionAnalysis(BaseModel):
    """情绪分析结果"""
    primary_emotion: EmotionType = Field(..., description="主要情绪")
    emotion_score: int = Field(..., ge=1, le=100, description="情绪评分(1-100)")
    emotion_intensity: EmotionIntensity = Field(..., description="情绪强度")
    emotion_distribution: Dict[str, float] = Field(..., description="情绪分布")
    key_sentences: List[str] = Field(default_factory=list, max_length=5, description="关键句子")
    
    @field_validator('emotion_distribution')
    @classmethod
    def validate_distribution(cls, v):
        """验证情绪分布总和为1.0"""
        total = sum(v.values())
        if not (0.99 <= total <= 1.01):  # 允许浮点误差
            raise ValueError(f"情绪分布总和必须为1.0，当前为{total}")
        return v
    
    @field_validator('emotion_score')
    @classmethod
    def validate_score_consistency(cls, v, info):
        """验证情绪评分与主要情绪的一致性"""
        primary = info.data.get('primary_emotion')
        if primary:
            if primary in [EmotionType.HAPPY, EmotionType.EXCITED] and v < 56:
                raise ValueError(f"积极情绪 {primary.value} 的评分应 >= 56，当前为 {v}")
            elif primary in [EmotionType.SAD, EmotionType.ANGRY, EmotionType.ANXIOUS] and v > 45:
                raise ValueError(f"消极情绪 {primary.value} 的评分应 <= 45，当前为 {v}")
        return v


class PersonMentioned(BaseModel):
    """提及的人物"""
    name: str = Field(..., min_length=1, max_length=50, description="人物姓名")
    relation: str = Field(..., min_length=1, max_length=50, description="关系")


class PlaceMentioned(BaseModel):
    """提及的地点"""
    name: str = Field(..., min_length=1, max_length=100, description="地点名称")
    type: str = Field(..., min_length=1, max_length=50, description="地点类型")


class ExtractionResultSchema(BaseModel):
    """提取结果 Schema"""
    summary: str = Field(..., min_length=10, max_length=500, description="日记摘要")
    keywords: List[str] = Field(..., min_length=1, max_length=10, description="关键词列表")
    main_topics: List[str] = Field(default_factory=list, max_length=10, description="主要话题列表")
    people_mentioned: List[PersonMentioned] = Field(default_factory=list, max_length=20, description="提及的人物")
    places_mentioned: List[PlaceMentioned] = Field(default_factory=list, max_length=20, description="提及的地点")
    emotion_analysis: EmotionAnalysis = Field(..., description="情绪分析")
    has_highlight: int = Field(..., ge=0, le=1, description="是否包含高光时刻(0/1)")
    highlight_summary: Optional[str] = Field(None, max_length=200, description="高光时刻摘要")
    has_small_happiness: int = Field(..., ge=0, le=1, description="是否包含小确幸(0/1)")
    small_happiness_content: Optional[str] = Field(None, max_length=200, description="小确幸内容")
    
    @field_validator('keywords')
    @classmethod
    def validate_keywords_length(cls, v):
        """验证关键词列表长度"""
        if not (1 <= len(v) <= 10):
            raise ValueError(f"关键词数量必须在1-10之间，当前为{len(v)}")
        return v
    
    @field_validator('main_topics')
    @classmethod
    def validate_topics_length(cls, v):
        """验证话题列表长度"""
        if len(v) > 10:
            raise ValueError(f"话题数量不能超过10个，当前为{len(v)}")
        return v
    
    @field_validator('people_mentioned')
    @classmethod
    def validate_people_length(cls, v):
        """验证人物列表长度"""
        if len(v) > 20:
            raise ValueError(f"人物数量不能超过20个，当前为{len(v)}")
        return v
    
    @field_validator('places_mentioned')
    @classmethod
    def validate_places_length(cls, v):
        """验证地点列表长度"""
        if len(v) > 20:
            raise ValueError(f"地点数量不能超过20个，当前为{len(v)}")
        return v
    
    @field_validator('highlight_summary')
    @classmethod
    def validate_highlight(cls, v, info):
        """验证高光时刻的一致性"""
        has_highlight = info.data.get('has_highlight')
        if has_highlight == 1 and not v:
            raise ValueError("has_highlight=1 时必须提供 highlight_summary")
        if has_highlight == 0 and v:
            raise ValueError("has_highlight=0 时不应提供 highlight_summary")
        return v
    
    @field_validator('small_happiness_content')
    @classmethod
    def validate_happiness(cls, v, info):
        """验证小确幸的一致性"""
        has_happiness = info.data.get('has_small_happiness')
        if has_happiness == 1 and not v:
            raise ValueError("has_small_happiness=1 时必须提供 small_happiness_content")
        if has_happiness == 0 and v:
            raise ValueError("has_small_happiness=0 时不应提供 small_happiness_content")
        return v




# ============================================================================
# 日记提取查询和导出响应模型 (Extraction Query & Export Response Models)
# ============================================================================

class DiarySummaryResponse(BaseModel):
    """日记摘要响应（完整版）"""
    diary_id: int = Field(..., description="日记ID")
    diary_date: date_type = Field(..., description="日记日期")
    summary: Optional[str] = Field(None, description="日记摘要")
    keywords: Optional[List[str]] = Field(None, description="关键词列表")
    main_topics: Optional[List[str]] = Field(None, description="主要话题列表")
    people_mentioned: Optional[List[Dict[str, str]]] = Field(None, description="提及的人物")
    places_mentioned: Optional[List[Dict[str, str]]] = Field(None, description="提及的地点")
    
    # 情绪分析（独立字段）
    primary_emotion: Optional[str] = Field(None, description="主要情绪")
    emotion_score: Optional[int] = Field(None, description="情绪评分(1-100)")
    emotion_intensity: Optional[str] = Field(None, description="情绪强度")
    emotion_distribution: Optional[Dict[str, float]] = Field(None, description="情绪分布")
    
    # 高光时刻和小确幸
    has_small_happiness: int = Field(..., description="是否包含小确幸(0/1)")
    small_happiness_content: Optional[str] = Field(None, description="小确幸内容")
    has_highlight: int = Field(..., description="是否包含高光时刻(0/1)")
    highlight_summary: Optional[str] = Field(None, description="高光时刻摘要")
    
    # 元数据
    extract_version: int = Field(..., description="提取版本")
    created_at: datetime = Field(..., description="创建时间")
    updated_at: datetime = Field(..., description="更新时间")
    
    model_config = ConfigDict(from_attributes=True)


class DiarySummarySimple(BaseModel):
    """日记摘要响应（简化版，供大模型使用）"""
    diary_id: int = Field(..., description="日记ID")
    diary_date: str = Field(..., description="日记日期(YYYY-MM-DD)")
    summary: str = Field(..., description="日记摘要")
    keywords: List[str] = Field(..., description="关键词列表")
    primary_emotion: str = Field(..., description="主要情绪")
    emotion_score: int = Field(..., description="情绪评分(1-100)")


class ModalityInsight(BaseModel):
    """Normalized insight for a single modality."""
    available: bool = Field(..., description="Whether the modality exists for the diary")
    emotion: Optional[str] = Field(None, description="Detected emotion label")
    emotion_score: Optional[int] = Field(None, ge=0, le=100, description="Emotion score")
    stress_score: Optional[int] = Field(None, ge=0, le=100, description="Stress score")
    positive_event_score: Optional[int] = Field(None, ge=0, le=100, description="Positive event score")
    confidence_score: Optional[int] = Field(None, ge=0, le=100, description="Confidence score")
    summary: Optional[str] = Field(None, description="Short modality summary")
    evidence: List[str] = Field(default_factory=list, description="Evidence phrases or signals")
    tags: List[str] = Field(default_factory=list, description="Structured modality tags")


class FusionInsight(BaseModel):
    """Fused multimodal interpretation for one diary."""
    overall_emotion: str = Field(..., description="Fused emotion label")
    overall_emotion_score: int = Field(..., ge=0, le=100, description="Fused emotion score")
    stress_score: int = Field(..., ge=0, le=100, description="Fused stress score")
    positive_event_score: int = Field(..., ge=0, le=100, description="Fused positive event score")
    confidence_score: int = Field(..., ge=0, le=100, description="Fusion confidence")
    emotional_stability: str = Field(..., description="Stability label")
    positive_events: List[str] = Field(default_factory=list, description="Positive event snippets")
    stress_triggers: List[str] = Field(default_factory=list, description="Stress trigger snippets")
    growth_keywords: List[str] = Field(default_factory=list, description="Growth keywords")
    explanation: str = Field(..., description="Human-readable explanation")


class DiaryMultimodalInsightResponse(BaseModel):
    """Per-diary multimodal insight response."""
    diary_id: int = Field(..., description="Diary id")
    diary_date: date_type = Field(..., description="Diary date")
    text_analysis: ModalityInsight
    voice_analysis: ModalityInsight
    image_analysis: ModalityInsight
    video_analysis: ModalityInsight
    drawing_analysis: ModalityInsight
    fusion_analysis: FusionInsight
    analysis_version: int = Field(..., description="Analysis version")
    created_at: datetime = Field(..., description="Created time")
    updated_at: datetime = Field(..., description="Updated time")


class GrowthTrendPoint(BaseModel):
    """Trend point in a growth portrait series."""
    date: date_type = Field(..., description="Point date")
    value: int = Field(..., ge=0, le=100, description="Metric value")
    label: Optional[str] = Field(None, description="Optional label")


class PositiveEventPoint(BaseModel):
    """Positive event item for portrait timeline."""
    diary_id: int = Field(..., description="Diary id")
    date: date_type = Field(..., description="Diary date")
    title: str = Field(..., description="Event title")
    summary: str = Field(..., description="Event summary")
    score: int = Field(..., ge=0, le=100, description="Positive score")


class GrowthPortraitResponse(BaseModel):
    """Longitudinal growth portrait response."""
    user_id: int = Field(..., description="User id")
    range_start: date_type = Field(..., description="Start date")
    range_end: date_type = Field(..., description="End date")
    emotion_curve: List[GrowthTrendPoint] = Field(default_factory=list)
    stress_curve: List[GrowthTrendPoint] = Field(default_factory=list)
    positive_events: List[PositiveEventPoint] = Field(default_factory=list)
    growth_keywords: List[str] = Field(default_factory=list)
    multimodal_distribution: Dict[str, int] = Field(default_factory=dict)
    summary: str = Field(..., description="Portrait summary")
    self_awareness_feedback: str = Field(..., description="Explainable self-awareness feedback")


class ExtractionJobResponse(BaseModel):
    """提取任务响应"""
    diary_id: int = Field(..., description="日记ID")
    status: str = Field(..., description="任务状态")
    attempts: int = Field(..., description="重试次数")
    started_at: Optional[str] = Field(None, description="开始时间")
    completed_at: Optional[str] = Field(None, description="完成时间")
    execution_time_ms: Optional[int] = Field(None, description="执行时间(毫秒)")
    error_message: Optional[str] = Field(None, description="错误信息")
    error_code: Optional[str] = Field(None, description="错误代码")
