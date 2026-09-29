package com.example.mydiary.data.repository

import com.example.mydiary.data.models.MediaUploadResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import com.example.mydiary.util.CacheManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体数据仓库
 * 
 * 职责：
 * - 处理媒体文件上传
 * - 管理上传进度
 * - 处理上传失败重试
 * - 管理文件缓存
 * 
 * 验证需求: 4.3, 5.5, 6.3
 * 性能优化: 任务31 - 集成文件缓存
 */
@Singleton
class MediaRepository @Inject constructor(
    private val apiService: DiaryApiService,
    private val cacheManager: CacheManager
) {
    
    /**
     * 上传媒体文件（内部 suspend 函数，不使用 Flow）
     * 
     * @param file 要上传的文件
     * @param diaryId 关联的日记ID
     * @param mediaType 媒体类型 (image/audio/video)
     * @return ApiResult<MediaUploadResponse> 上传结果
     */
    private suspend fun uploadMediaInternal(
        file: File,
        diaryId: Long,
        mediaType: String
    ): ApiResult<MediaUploadResponse> {
        return try {
            // 确定MIME类型
            val mimeType = when (mediaType.lowercase()) {
                "image" -> "image/*"
                "audio" -> "audio/*"
                "video" -> "video/*"
                else -> "application/octet-stream"
            }
            
            // 创建文件请求体
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.name,
                requestFile
            )
            
            // 创建其他参数
            val diaryIdBody = diaryId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val mediaTypeBody = mediaType.toRequestBody("text/plain".toMediaTypeOrNull())
            
            // 调用API
            SafeApiCall.execute {
                apiService.uploadMedia(
                    file = filePart,
                    diaryId = diaryIdBody,
                    mediaType = mediaTypeBody
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(
                code = -1,
                message = "文件上传准备失败: ${e.message}",
                exception = e
            )
        }
    }
    
    /**
     * 上传媒体文件
     * 
     * 验证需求: 4.3, 5.5, 6.3
     * 
     * @param file 要上传的文件
     * @param diaryId 关联的日记ID
     * @param mediaType 媒体类型 (image/audio/video)
     * @return Flow<ApiResult<MediaUploadResponse>> 上传结果数据流（只 emit 一次最终结果）
     */
    fun uploadMedia(
        file: File,
        diaryId: Long,
        mediaType: String
    ): Flow<ApiResult<MediaUploadResponse>> = flow {
        // ✅ 只 emit 一次最终结果，避免 AbortFlowException
        // Loading 状态由 ViewModel/UI 的 isUploading 维护
        emit(uploadMediaInternal(file, diaryId, mediaType))
    }
    
    /**
     * 上传媒体文件（带重试）- suspend 版本
     * 
     * 验证需求: 4.3
     * 
     * @param file 要上传的文件
     * @param diaryId 关联的日记ID
     * @param mediaType 媒体类型 (image/audio/video)
     * @param maxRetries 最大重试次数
     * @return ApiResult<MediaUploadResponse> 上传结果
     */
    suspend fun uploadMediaWithRetrySuspend(
        file: File,
        diaryId: Long,
        mediaType: String,
        maxRetries: Int = 3
    ): ApiResult<MediaUploadResponse> {
        var lastError: ApiResult.Error? = null
        
        for (attempt in 1..maxRetries) {
            val result = uploadMediaInternal(file, diaryId, mediaType)
            
            when (result) {
                is ApiResult.Success -> {
                    return result
                }
                is ApiResult.Error -> {
                    lastError = result
                    if (attempt < maxRetries) {
                        delay(1000L * attempt) // 递增延迟
                    }
                }
                else -> { /* Loading 不会出现 */ }
            }
        }
        
        return lastError?.copy(
            message = "上传失败（已重试${maxRetries}次）: ${lastError.message}"
        ) ?: ApiResult.Error(
            code = -1,
            message = "上传失败（已重试${maxRetries}次）"
        )
    }
    
    /**
     * 上传媒体文件（带重试）- Flow 版本
     * 
     * 验证需求: 4.3
     * 
     * @param file 要上传的文件
     * @param diaryId 关联的日记ID
     * @param mediaType 媒体类型 (image/audio/video)
     * @param maxRetries 最大重试次数
     * @return Flow<ApiResult<MediaUploadResponse>> 上传结果数据流（只 emit 一次最终结果）
     */
    fun uploadMediaWithRetry(
        file: File,
        diaryId: Long,
        mediaType: String,
        maxRetries: Int = 3
    ): Flow<ApiResult<MediaUploadResponse>> = flow {
        // ✅ 只 emit 一次最终结果，避免 AbortFlowException
        // Loading 状态由 ViewModel/UI 的 isUploading 维护
        emit(uploadMediaWithRetrySuspend(file, diaryId, mediaType, maxRetries))
    }
    
    /**
     * 验证文件是否有效
     * 
     * @param file 要验证的文件
     * @param mediaType 媒体类型
     * @return 验证结果，null表示有效，否则返回错误信息
     */
    fun validateFile(file: File, mediaType: String): String? {
        // 检查文件是否存在
        if (!file.exists()) {
            return "文件不存在"
        }
        
        // 检查文件是否可读
        if (!file.canRead()) {
            return "文件无法读取"
        }
        
        // 检查文件大小（例如：限制为50MB）
        val maxSize = 50 * 1024 * 1024 // 50MB
        if (file.length() > maxSize) {
            return "文件过大（最大50MB）"
        }
        
        // 检查文件扩展名
        val extension = file.extension.lowercase()
        val validExtensions = when (mediaType.lowercase()) {
            "image" -> listOf("jpg", "jpeg", "png", "gif", "webp")
            "audio" -> listOf("mp3", "wav", "m4a", "aac", "ogg")
            "video" -> listOf("mp4", "mov", "avi", "mkv", "webm")
            else -> emptyList()
        }
        
        if (validExtensions.isNotEmpty() && extension !in validExtensions) {
            return "不支持的文件格式: .$extension"
        }
        
        return null // 验证通过
    }
    
    /**
     * 获取缓存的媒体文件
     * 
     * @param url 媒体URL
     * @return 缓存的文件，如果不存在则返回null
     * 
     * 性能优化: 任务31 - 文件缓存
     */
    fun getCachedMedia(url: String): File? {
        return cacheManager.getCachedFile(url)
    }
    
    /**
     * 下载并缓存媒体文件
     * 
     * @param url 媒体URL
     * @return Flow<ApiResult<File>> 下载结果
     * 
     * 性能优化: 任务31 - 文件缓存
     */
    fun downloadAndCacheMedia(url: String): Flow<ApiResult<File>> = flow {
        emit(ApiResult.Loading)
        
        try {
            // 检查缓存
            val cachedFile = cacheManager.getCachedFile(url)
            if (cachedFile != null) {
                emit(ApiResult.Success(cachedFile))
                return@flow
            }
            
            // 下载并缓存
            val file = cacheManager.downloadAndCache(url)
            emit(ApiResult.Success(file))
            
        } catch (e: Exception) {
            emit(ApiResult.Error(
                code = -1,
                message = "文件下载失败: ${e.message}",
                exception = e
            ))
        }
    }
    
    /**
     * 清理媒体缓存
     */
    fun clearMediaCache() {
        cacheManager.clearAllCache()
    }
    
    /**
     * 获取缓存大小（MB）
     * 
     * @return 缓存大小
     */
    fun getCacheSizeMB(): Double {
        return cacheManager.getCacheSizeMB()
    }
}
