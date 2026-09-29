package com.example.mydiary.data.models

import com.example.mydiary.data.network.ApiEndpoints
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate

/**
 * 数据模型定义
 * 用于网络请求和响应的序列化/反序列化
 */

// ============================================================================
// 请求模型 (Request Models)
// ============================================================================

/**
 * 创建日记请求
 */
data class CreateDiaryRequest(
    @SerializedName("user_id")
    val userId: Long,
    
    @SerializedName("title")
    val title: String? = null,
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("diary_date")
    val diaryDate: String, // YYYY-MM-DD format
    
    @SerializedName("weather")
    val weather: String? = null,
    
    @SerializedName("location")
    val location: String? = null,
    
    @SerializedName("mood_score")
    val moodScore: Int? = null,
    
    @SerializedName("mood_type")
    val moodType: String? = null,
    
    @SerializedName("is_private")
    val isPrivate: Int = 1
)

/**
 * 更新日记请求
 */
data class UpdateDiaryRequest(
    @SerializedName("title")
    val title: String? = null,
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("weather")
    val weather: String? = null,
    
    @SerializedName("location")
    val location: String? = null,
    
    @SerializedName("mood_score")
    val moodScore: Int? = null,
    
    @SerializedName("mood_type")
    val moodType: String? = null,
    
    @SerializedName("is_private")
    val isPrivate: Int? = null,
    
    @SerializedName("is_highlight")
    val isHighlight: Int? = null,
    
    @SerializedName("is_little_joy")
    val isLittleJoy: Int? = null
)

/**
 * 同步媒体项请求（单个）
 * 用于批量同步媒体项
 */
data class SyncMediaItemRequest(
    @SerializedName("id")
    val id: Long? = null,  // null 表示新增（BlockId，日记内容块的ID）
    
    @SerializedName("asset_id")
    val assetId: Long? = null,  // 关联的上传资源ID（AssetId），可选
    
    @SerializedName("media_type")
    val mediaType: String,
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("media_url")
    val mediaUrl: String? = null,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @SerializedName("duration")
    val duration: Int? = null,
    
    @SerializedName("file_size")
    val fileSize: Long? = null,
    
    @SerializedName("sort_order")
    val sortOrder: Int
)

/**
 * 批量同步媒体请求（全量同步）
 */
data class SyncMediaRequest(
    @SerializedName("items")
    val items: List<SyncMediaItemRequest>
)

// ============================================================================
// 响应模型 (Response Models)
// ============================================================================

/**
 * 媒体项响应
 */
data class MediaItemResponse(
    @SerializedName("id")
    val id: Long,  // BlockId，日记内容块的ID
    
    @SerializedName("diary_id")
    val diaryId: Long,
    
    @SerializedName("asset_id")
    val assetId: Long? = null,  // AssetId，关联的上传资源ID
    
    @SerializedName("media_type")
    val mediaType: String, // text/image/audio/video
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("media_url")
    val mediaUrl: String? = null,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @SerializedName("duration")
    val duration: Int? = null,
    
    @SerializedName("file_size")
    val fileSize: Long? = null,
    
    @SerializedName("sort_order")
    val sortOrder: Int,
    
    @SerializedName("created_at")
    val createdAt: String
)

/**
 * 日记响应
 */
data class DiaryResponse(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("user_id")
    val userId: Long,
    
    @SerializedName("title")
    val title: String? = null,
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("diary_date")
    val diaryDate: String, // YYYY-MM-DD format
    
    @SerializedName("weather")
    val weather: String? = null,
    
    @SerializedName("location")
    val location: String? = null,
    
    @SerializedName("mood_score")
    val moodScore: Int? = null,
    
    @SerializedName("mood_type")
    val moodType: String? = null,
    
    @SerializedName("is_private")
    val isPrivate: Int,
    
    @SerializedName("is_extracted")
    val isExtracted: Int,
    
    @SerializedName("is_highlight")
    val isHighlight: Int = 0,
    
    @SerializedName("is_little_joy")
    val isLittleJoy: Int = 0,

    @SerializedName("word_count")
    val wordCount: Int,

    // 提取相关字段
    @SerializedName("extraction_status")
    val extractionStatus: String? = null,

    @SerializedName("content_hash")
    val contentHash: String? = null,

    @SerializedName("extract_version")
    val extractVersion: Int? = null,

    // 向量化相关字段
    @SerializedName("embedding_status")
    val embeddingStatus: String? = null,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("updated_at")
    val updatedAt: String,

    @SerializedName("media_items")
    val mediaItems: List<MediaItemResponse> = emptyList()
)

/**
 * 情感分析结果
 */
data class EmotionResult(
    @SerializedName("emotion_type")
    val emotionType: String,
    
    @SerializedName("score")
    val score: Int, // 1-100
    
    @SerializedName("confidence")
    val confidence: Float, // 0-1
    
    @SerializedName("details")
    val details: Map<String, Any>? = null
)

/**
 * 语音处理结果
 */
data class VoiceProcessingResult(
    @SerializedName("original_text")
    val originalText: String,
    
    @SerializedName("processed_text")
    val processedText: String,
    
    @SerializedName("emotion")
    val emotion: EmotionResult,
    
    @SerializedName("request_id")
    val requestId: String,
    
    @SerializedName("timestamp")
    val timestamp: String
)

/**
 * 语音处理（带选项）响应
 * 
 * 根据 save_mode 返回不同的处理结果：
 * - save_mode=1: 仅保存音频 + 语音情感分析
 * - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项）
 * - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析
 * 
 * 状态说明：
 * - status="success": 处理完成
 * - status="processing": ASR 仍在处理中，需要轮询 /voice/asr-status
 * - status="failure": 处理失败
 * 
 * 需求: 1.2, 1.3, 1.4, 2.1, 3.1
 */
data class VoiceProcessWithOptionsResponse(
    @SerializedName("success")
    val success: Boolean,
    
    @SerializedName("status")
    val status: String? = "success",  // success | processing | failure
    
    @SerializedName("task_id")
    val taskId: String? = null,  // ASR 任务ID（用于查询状态）
    
    @SerializedName("media_id")
    val mediaId: Long? = null,  // 音频媒体ID（save_mode=1或3时）
    
    @SerializedName("media_url")
    val mediaUrl: String? = null,  // ✅ 音频媒体URL（save_mode=1或3时）
    
    @SerializedName("text_media_id")
    val textMediaId: Long? = null,  // 文本媒体ID（save_mode=2或3时）
    
    @SerializedName("transcription_id")
    val transcriptionId: Long? = null,  // 转录记录ID
    
    @SerializedName("original_text")
    val originalText: String? = null,  // 原始转录文本
    
    @SerializedName("processed_text")
    val processedText: String? = null,  // 处理后的流畅文本
    
    @SerializedName("emotion")
    val emotion: EmotionResult? = null,  // 情感分析结果
    
    @SerializedName("error")
    val error: String? = null,  // 错误信息（如果有）
    
    @SerializedName("message")
    val message: String? = null,  // 状态消息
    
    @SerializedName("asr_status")
    val asrStatus: String? = null  // 百度 ASR 原始状态
) {
    /**
     * 是否正在处理中（需要轮询）
     */
    fun isProcessing(): Boolean = status == "processing"
    
    /**
     * 是否处理完成
     */
    fun isSuccess(): Boolean = status == "success"
    
    /**
     * 是否处理失败
     */
    fun isFailure(): Boolean = status == "failure"
}

/**
 * ASR 状态查询响应
 */
data class AsrStatusResponse(
    @SerializedName("status")
    val status: String,  // success | processing | failure
    
    @SerializedName("task_id")
    val taskId: String,
    
    @SerializedName("text")
    val text: String? = null,  // 转写文本（success 时）
    
    @SerializedName("error_code")
    val errorCode: Int? = null,  // 错误码（failure 时）
    
    @SerializedName("error_msg")
    val errorMsg: String? = null,  // 错误信息（failure 时）
    
    @SerializedName("asr_status")
    val asrStatus: String? = null  // 百度 ASR 原始状态
) {
    fun isProcessing(): Boolean = status == "processing"
    fun isSuccess(): Boolean = status == "success"
    fun isFailure(): Boolean = status == "failure"
}

/**
 * 媒体上传响应
 */
data class MediaUploadResponse(
    @SerializedName("media_id")
    val mediaId: Long,
    
    @SerializedName("media_url")
    val mediaUrl: String,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @SerializedName("file_size")
    val fileSize: Long,
    
    @SerializedName("message")
    val message: String
)

/**
 * 有日记的日期列表响应
 */
data class DatesWithDiaryResponse(
    @SerializedName("dates")
    val dates: List<String>, // YYYY-MM-DD format
    
    @SerializedName("year")
    val year: Int,
    
    @SerializedName("month")
    val month: Int,
    
    @SerializedName("count")
    val count: Int
)

/**
 * 语音转录响应（仅转录）
 */
data class TranscriptionResponse(
    @SerializedName("text")
    val text: String,
    
    @SerializedName("filename")
    val filename: String,
    
    @SerializedName("timestamp")
    val timestamp: String
)

/**
 * 错误响应
 */
data class ErrorResponse(
    @SerializedName("error")
    val error: String,
    
    @SerializedName("detail")
    val detail: String? = null,
    
    @SerializedName("timestamp")
    val timestamp: String,
    
    @SerializedName("request_id")
    val requestId: String? = null
)

// ============================================================================
// 领域模型 (Domain Models)
// ============================================================================

/**
 * 日记领域模型
 * 用于应用内部的业务逻辑
 */
data class Diary(
    val id: Long?,
    val userId: Long,
    val title: String?,
    val date: LocalDate,
    val mediaItems: List<MediaItem>,
    val weather: String?,
    val location: String?,
    val moodScore: Int?,
    val moodType: String?,
    val isHighlight: Boolean = false,
    val isLittleJoy: Boolean = false,
    // 提取相关字段
    val extractionStatus: String? = null,
    val contentHash: String? = null,
    val extractVersion: Int? = null,
    // 向量化相关字段
    val embeddingStatus: String? = null,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

/**
 * 媒体项密封类
 * 表示日记中的不同类型媒体内容
 * 
 * id: BlockId（日记内容块的ID）
 * assetId: AssetId（关联的上传资源ID，可选）
 */
sealed class MediaItem {
    abstract val id: String
    abstract val assetId: Long?
    abstract val order: Int
    abstract val createdAt: Instant
    
    /**
     * 文字媒体项
     */
    data class Text(
        override val id: String,
        override val assetId: Long? = null,
        val content: String,
        override val order: Int,
        override val createdAt: Instant
    ) : MediaItem()
    
    /**
     * 图片媒体项
     */
    data class Image(
        override val id: String,
        override val assetId: Long? = null,
        val url: String,
        val thumbnailUrl: String?,
        override val order: Int,
        override val createdAt: Instant
    ) : MediaItem()
    
    /**
     * 音频媒体项
     */
    data class Audio(
        override val id: String,
        override val assetId: Long? = null,
        val url: String,
        val duration: Int,
        val transcription: VoiceTranscription?,
        override val order: Int,
        override val createdAt: Instant
    ) : MediaItem()
    
    /**
     * 视频媒体项
     */
    data class Video(
        override val id: String,
        override val assetId: Long? = null,
        val url: String,
        val thumbnailUrl: String?,
        val duration: Int,
        override val order: Int,
        override val createdAt: Instant
    ) : MediaItem()
}

/**
 * 语音转录
 * 包含原始文本、处理后文本和情感分析结果
 */
data class VoiceTranscription(
    val originalText: String,
    val processedText: String,
    val emotion: EmotionAnalysis
)

/**
 * 情感分析
 * 包含情感类型、评分和置信度
 */
data class EmotionAnalysis(
    val emotionType: String,  // 快乐、悲伤、愤怒、中性等
    val score: Int,  // 1-100
    val confidence: Float  // 0-1
)


// ============================================================================
// 映射扩展函数 (Mapper Extension Functions)
// ============================================================================

/**
 * 将DiaryResponse转换为Diary领域模型
 * 兼容两种存储方式：老数据 content、新数据 media_items
 */
fun DiaryResponse.toDomain(): Diary {
    val mapped = this.mediaItems.map { it.toDomain() }.toMutableList()
    
    // 兜底：如果后端把正文放在 content，而不是 media_items
    if (mapped.none { it is MediaItem.Text } && !this.content.isNullOrBlank()) {
        mapped.add(
            MediaItem.Text(
                id = "content_${this.id}",
                content = this.content!!,
                order = 0,
                createdAt = parseInstant(this.createdAt)
            )
        )
    }
    
    return Diary(
        id = this.id,
        userId = this.userId,
        title = this.title,
        date = LocalDate.parse(this.diaryDate),
        mediaItems = mapped.sortedBy { it.order },
        weather = this.weather,
        location = this.location,
        moodScore = this.moodScore,
        moodType = this.moodType,
        isHighlight = this.isHighlight == 1,
        isLittleJoy = this.isLittleJoy == 1,
        extractionStatus = this.extractionStatus,
        contentHash = this.contentHash,
        extractVersion = this.extractVersion,
        embeddingStatus = this.embeddingStatus,
        createdAt = parseInstant(this.createdAt),
        updatedAt = parseInstant(this.updatedAt)
    )
}

/**
 * 媒体URL基础地址（用于拼接相对路径）
 * 
 * 开发环境配置:
 * - Android 模拟器: http://10.0.2.2:8000
 * - 真实设备: http://你的电脑IP:8000 (例如 http://192.168.1.100:8000)
 * - 云服务器: http://121.199.40.1
 */
/**
 * 将相对媒体URL转换为完整URL
 * 
 * @param relativeUrl 相对URL（如 /media/2026/01/08/image/xxx.jpg）
 * @return 完整URL（如 http://10.0.2.2:8000/media/2026/01/08/image/xxx.jpg）
 */
fun buildFullMediaUrl(relativeUrl: String?): String? {
    if (relativeUrl.isNullOrBlank()) return null
    // 如果已经是完整URL，直接返回
    if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
        return relativeUrl
    }
    // 拼接基础URL
    return "${ApiEndpoints.mediaBaseUrl}$relativeUrl"
}

/**
 * 将MediaItemResponse转换为MediaItem领域模型
 */
fun MediaItemResponse.toDomain(): MediaItem {
    val createdAt = parseInstant(this.createdAt)
    
    return when (this.mediaType.lowercase()) {
        "text" -> MediaItem.Text(
            id = this.id.toString(),
            assetId = this.assetId,
            content = this.content ?: "",
            order = this.sortOrder,
            createdAt = createdAt
        )
        "image" -> MediaItem.Image(
            id = this.id.toString(),
            assetId = this.assetId,
            url = buildFullMediaUrl(this.mediaUrl) ?: "",
            thumbnailUrl = buildFullMediaUrl(this.thumbnailUrl),
            order = this.sortOrder,
            createdAt = createdAt
        )
        "audio" -> MediaItem.Audio(
            id = this.id.toString(),
            assetId = this.assetId,
            url = buildFullMediaUrl(this.mediaUrl) ?: "",
            duration = this.duration ?: 0,
            transcription = null, // Will be populated separately if needed
            order = this.sortOrder,
            createdAt = createdAt
        )
        "video" -> MediaItem.Video(
            id = this.id.toString(),
            assetId = this.assetId,
            url = buildFullMediaUrl(this.mediaUrl) ?: "",
            thumbnailUrl = buildFullMediaUrl(this.thumbnailUrl),
            duration = this.duration ?: 0,
            order = this.sortOrder,
            createdAt = createdAt
        )
        else -> throw IllegalArgumentException("Unknown media type: ${this.mediaType}")
    }
}

/**
 * 将EmotionResult转换为EmotionAnalysis领域模型
 */
fun EmotionResult.toDomain(): EmotionAnalysis {
    return EmotionAnalysis(
        emotionType = this.emotionType,
        score = this.score,
        confidence = this.confidence
    )
}

/**
 * 将VoiceProcessingResult转换为VoiceTranscription领域模型
 */
fun VoiceProcessingResult.toVoiceTranscription(): VoiceTranscription {
    return VoiceTranscription(
        originalText = this.originalText,
        processedText = this.processedText,
        emotion = this.emotion.toDomain()
    )
}

/**
 * 将Diary领域模型转换为CreateDiaryRequest
 */
fun Diary.toCreateRequest(): CreateDiaryRequest {
    return CreateDiaryRequest(
        userId = this.userId,
        title = this.title,
        content = null, // Content is stored in media items
        diaryDate = this.date.toString(),
        weather = this.weather,
        location = this.location,
        moodScore = this.moodScore,
        moodType = this.moodType,
        isPrivate = 1
    )
}

/**
 * 将Diary领域模型转换为UpdateDiaryRequest
 */
fun Diary.toUpdateRequest(): UpdateDiaryRequest {
    return UpdateDiaryRequest(
        title = this.title,
        content = null, // Content is stored in media items
        weather = this.weather,
        location = this.location,
        moodScore = this.moodScore,
        moodType = this.moodType,
        isPrivate = null,
        isHighlight = if (this.isHighlight) 1 else 0,
        isLittleJoy = if (this.isLittleJoy) 1 else 0
    )
}

// ============================================================================
// 辅助函数 (Helper Functions)
// ============================================================================

/**
 * 解析ISO 8601时间戳字符串为Instant
 * 支持多种格式
 */
private fun parseInstant(timestamp: String): Instant {
    return try {
        // Try parsing as ISO 8601 with timezone
        Instant.parse(timestamp)
    } catch (e: Exception) {
        try {
            // Try parsing as local datetime and assume UTC
            val localDateTime = java.time.LocalDateTime.parse(
                timestamp.replace(" ", "T")
            )
            localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
        } catch (e2: Exception) {
            // Fallback to current time if parsing fails
            Instant.now()
        }
    }
}


// ============================================================================
// 媒体项扩展函数 (MediaItem Extension Functions)
// ============================================================================

/**
 * 创建一个新的 MediaItem，只修改 order，保留其他所有字段（包括 assetId）
 * 
 * @param newOrder 新的排序顺序
 * @return 新的 MediaItem 实例
 */
fun MediaItem.withOrder(newOrder: Int): MediaItem {
    return when (this) {
        is MediaItem.Text -> this.copy(order = newOrder)
        is MediaItem.Image -> this.copy(order = newOrder)
        is MediaItem.Audio -> this.copy(order = newOrder)
        is MediaItem.Video -> this.copy(order = newOrder)
    }
}

/**
 * 将 MediaItem 转换为 SyncMediaItemRequest
 * 
 * 处理临时 id：
 * - 如果 id 是纯数字（真实 id）→ 保留
 * - 如果 id 不是纯数字（临时 id，如 "text_123456"）→ 转为 null
 * 
 * @return SyncMediaItemRequest 请求模型
 */
fun MediaItem.toSyncRequest(): SyncMediaItemRequest {
    // 判断 id 是否为纯数字（真实 id）还是临时 id
    val mediaId = this.id.toLongOrNull()
    
    return when (this) {
        is MediaItem.Text -> SyncMediaItemRequest(
            id = mediaId,
            assetId = this.assetId,
            mediaType = "text",
            content = this.content,
            mediaUrl = null,
            thumbnailUrl = null,
            duration = null,
            fileSize = null,
            sortOrder = this.order
        )
        is MediaItem.Image -> SyncMediaItemRequest(
            id = mediaId,
            assetId = this.assetId,
            mediaType = "image",
            content = null,
            mediaUrl = this.url,
            thumbnailUrl = this.thumbnailUrl,
            duration = null,
            fileSize = null,
            sortOrder = this.order
        )
        is MediaItem.Audio -> SyncMediaItemRequest(
            id = mediaId,
            assetId = this.assetId,
            mediaType = "audio",
            content = null,
            mediaUrl = this.url,
            thumbnailUrl = null,
            duration = this.duration,
            fileSize = null,
            sortOrder = this.order
        )
        is MediaItem.Video -> SyncMediaItemRequest(
            id = mediaId,
            assetId = this.assetId,
            mediaType = "video",
            content = null,
            mediaUrl = this.url,
            thumbnailUrl = this.thumbnailUrl,
            duration = this.duration,
            fileSize = null,
            sortOrder = this.order
        )
    }
}

/**
 * 将 MediaItem 列表转换为 SyncMediaRequest
 * 
 * @return SyncMediaRequest 请求模型
 */
fun List<MediaItem>.toSyncRequest(): SyncMediaRequest {
    return SyncMediaRequest(
        items = this.map { it.toSyncRequest() }
    )
}
