package com.example.mydiary.data.repository

import com.example.mydiary.data.models.AsrStatusResponse
import com.example.mydiary.data.models.EmotionResult
import com.example.mydiary.data.models.TranscriptionResponse
import com.example.mydiary.data.models.VoiceProcessingResult
import com.example.mydiary.data.models.VoiceProcessWithOptionsResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
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
 * 语音处理数据仓库
 * 
 * 职责：
 * - 处理语音转文字
 * - 处理情感分析
 * - 处理统一语音处理流程
 * - 处理带选项的语音处理流程
 * 
 * 验证需求: 1.2, 1.3, 1.4, 8.1, 9.1, 10.1, 11.1
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val apiService: DiaryApiService
) {
    
    /**
     * 带选项的语音处理
     * 根据 save_mode 执行不同的处理流程：
     * - save_mode=1: 仅保存音频 + 语音情感分析
     * - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项）
     * - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析
     * 
     * 验证需求: 1.2, 1.3, 1.4
     * 
     * @param audioFile 音频文件
     * @param diaryId 关联的日记ID
     * @param userId 用户ID
     * @param saveMode 保存模式 (1=仅语音, 2=仅文字, 3=两者都保存)
     * @param mediaId 媒体ID（save_mode=1或3时需要）
     * @return Flow<ApiResult<VoiceProcessWithOptionsResponse>> 处理结果数据流
     */
    fun processVoiceWithOptions(
        audioFile: File,
        diaryId: Long,
        userId: Long,
        saveMode: Int,
        mediaId: Long? = null
    ): Flow<ApiResult<VoiceProcessWithOptionsResponse>> = flow {
        emit(ApiResult.Loading)
        
        try {
            // 验证 save_mode
            if (saveMode !in 1..3) {
                emit(ApiResult.Error(
                    code = -1,
                    message = "无效的 save_mode: $saveMode，有效值为 1, 2, 3"
                ))
                return@flow
            }
            
            // 验证 media_id（save_mode=1或3时需要）
            if (saveMode in listOf(1, 3) && mediaId == null) {
                emit(ApiResult.Error(
                    code = -1,
                    message = "save_mode=$saveMode 时需要提供 media_id"
                ))
                return@flow
            }
            
            // 创建音频文件请求体
            // 修复：使用固定的文件名和正确的 Content-Type，避免文件名后缀错误
            val extension = audioFile.extension.lowercase()
            val contentType = when (extension) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "audio/mp4" // 默认使用 m4a 的 MIME 类型
            }
            val fixedExtension = if (extension in listOf("mp3", "m4a", "wav", "ogg", "flac", "webm")) {
                extension
            } else {
                "m4a" // 默认扩展名
            }
            val fixedFilename = "audio_${System.currentTimeMillis()}.$fixedExtension"
            
            val requestFile = audioFile.asRequestBody(contentType.toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData(
                "audio",
                fixedFilename,
                requestFile
            )
            
            // 创建其他参数
            val diaryIdBody = diaryId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val userIdBody = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val saveModeBody = saveMode.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val mediaIdBody = mediaId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            
            // 调用API
            val result = SafeApiCall.execute {
                apiService.processVoiceWithOptions(
                    audio = audioPart,
                    diaryId = diaryIdBody,
                    userId = userIdBody,
                    saveMode = saveModeBody,
                    mediaId = mediaIdBody
                )
            }
            
            // 发送结果
            emit(result)
            
        } catch (e: Exception) {
            emit(ApiResult.Error(
                code = -1,
                message = "语音处理准备失败: ${e.message}",
                exception = e
            ))
        }
    }
    
    /**
     * 统一语音处理
     * 执行完整流程：语音转文字 -> 去除冗余词 -> 情感分析
     * 
     * 验证需求: 8.1, 9.1, 10.1, 11.1
     * 
     * @param audioFile 音频文件
     * @param diaryId 关联的日记ID
     * @param mediaId 关联的媒体ID
     * @return Flow<ApiResult<VoiceProcessingResult>> 处理结果数据流
     */
    fun processVoice(
        audioFile: File,
        diaryId: Long,
        mediaId: Long
    ): Flow<ApiResult<VoiceProcessingResult>> = flow {
        emit(ApiResult.Loading)
        
        try {
            // 创建音频文件请求体
            // 修复：使用固定的文件名和正确的 Content-Type，避免文件名后缀错误
            val extension = audioFile.extension.lowercase()
            val contentType = when (extension) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "audio/mp4" // 默认使用 m4a 的 MIME 类型
            }
            val fixedExtension = if (extension in listOf("mp3", "m4a", "wav", "ogg", "flac", "webm")) {
                extension
            } else {
                "m4a" // 默认扩展名
            }
            val fixedFilename = "audio_${System.currentTimeMillis()}.$fixedExtension"
            
            val requestFile = audioFile.asRequestBody(contentType.toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData(
                "audio",
                fixedFilename,
                requestFile
            )
            
            // 创建其他参数
            val diaryIdBody = diaryId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val mediaIdBody = mediaId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            
            // 调用API
            val result = SafeApiCall.execute {
                apiService.processVoice(
                    audio = audioPart,
                    diaryId = diaryIdBody,
                    mediaId = mediaIdBody
                )
            }
            
            // 发送结果
            emit(result)
            
        } catch (e: Exception) {
            emit(ApiResult.Error(
                code = -1,
                message = "语音处理准备失败: ${e.message}",
                exception = e
            ))
        }
    }
    
    /**
     * 仅语音转文字
     * 
     * 验证需求: 8.1, 8.2, 8.3, 8.4
     * 
     * @param audioFile 音频文件
     * @return Flow<ApiResult<TranscriptionResponse>> 转录结果数据流
     */
    fun transcribeAudio(
        audioFile: File
    ): Flow<ApiResult<TranscriptionResponse>> = flow {
        emit(ApiResult.Loading)
        
        try {
            // 创建音频文件请求体
            // 修复：使用固定的文件名和正确的 Content-Type，避免文件名后缀错误
            val extension = audioFile.extension.lowercase()
            val contentType = when (extension) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "audio/mp4" // 默认使用 m4a 的 MIME 类型
            }
            val fixedExtension = if (extension in listOf("mp3", "m4a", "wav", "ogg", "flac", "webm")) {
                extension
            } else {
                "m4a" // 默认扩展名
            }
            val fixedFilename = "audio_${System.currentTimeMillis()}.$fixedExtension"
            
            val requestFile = audioFile.asRequestBody(contentType.toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData(
                "audio",
                fixedFilename,
                requestFile
            )
            
            // 调用API
            val result = SafeApiCall.execute {
                apiService.transcribeAudio(audioPart)
            }
            
            // 发送结果
            emit(result)
            
        } catch (e: Exception) {
            emit(ApiResult.Error(
                code = -1,
                message = "语音转文字准备失败: ${e.message}",
                exception = e
            ))
        }
    }
    
    /**
     * 仅情感分析
     * 
     * 验证需求: 10.1, 10.2, 10.3, 10.4, 10.6
     * 
     * @param text 待分析的文本
     * @return Flow<ApiResult<EmotionResult>> 情感分析结果数据流
     */
    fun analyzeEmotion(
        text: String
    ): Flow<ApiResult<EmotionResult>> = flow {
        emit(ApiResult.Loading)
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.analyzeEmotion(text)
        }
        
        // 发送结果
        emit(result)
    }
    
    /**
     * 查询 ASR 状态（单次查询）
     * 
     * @param taskId ASR 任务ID
     * @return Flow<ApiResult<AsrStatusResponse>> 状态查询结果
     */
    fun getAsrStatus(taskId: String): Flow<ApiResult<AsrStatusResponse>> = flow {
        emit(ApiResult.Loading)
        
        val result = SafeApiCall.execute {
            apiService.getAsrStatus(taskId)
        }
        
        emit(result)
    }
    
    /**
     * 轮询 ASR 状态直到完成或超时
     * 
     * 在收到 status=processing 后调用此方法轮询最终结果。
     * 
     * @param taskId ASR 任务ID
     * @param pollIntervalMs 轮询间隔（毫秒），默认 4000ms
     * @param maxAttempts 最大轮询次数，默认 60 次（约 4 分钟）
     * @return Flow<ApiResult<AsrStatusResponse>> 最终结果或超时
     */
    fun pollAsrStatus(
        taskId: String,
        pollIntervalMs: Long = 4000L,
        maxAttempts: Int = 60
    ): Flow<ApiResult<AsrStatusResponse>> = flow {
        emit(ApiResult.Loading)
        
        var attempt = 0
        
        while (attempt < maxAttempts) {
            attempt++
            
            val result = SafeApiCall.execute {
                apiService.getAsrStatus(taskId)
            }
            
            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    
                    if (response.isSuccess() || response.isFailure()) {
                        // 处理完成（成功或失败），返回结果
                        emit(result)
                        return@flow
                    }
                    
                    // 仍在处理中，继续轮询
                    // 每 5 次轮询发送一次中间状态更新
                    if (attempt % 5 == 0) {
                        emit(result)
                    }
                }
                is ApiResult.Error -> {
                    // 网络错误等，继续尝试
                    if (attempt >= maxAttempts) {
                        emit(result)
                        return@flow
                    }
                }
                is ApiResult.Loading -> {
                    // 忽略
                }
            }
            
            // 等待后继续轮询
            delay(pollIntervalMs)
        }
        
        // 超时
        emit(ApiResult.Error(
            code = -1,
            message = "ASR 状态查询超时，请稍后重试"
        ))
    }
    
    /**
     * 验证音频文件是否有效
     * 
     * 验证需求: 8.2
     * 
     * @param audioFile 要验证的音频文件
     * @return 验证结果，null表示有效，否则返回错误信息
     */
    fun validateAudioFile(audioFile: File): String? {
        // 检查文件是否存在
        if (!audioFile.exists()) {
            return "音频文件不存在"
        }
        
        // 检查文件是否可读
        if (!audioFile.canRead()) {
            return "音频文件无法读取"
        }
        
        // 检查文件大小（例如：限制为25MB）
        val maxSize = 25 * 1024 * 1024 // 25MB
        if (audioFile.length() > maxSize) {
            return "音频文件过大（最大25MB）"
        }
        
        // 检查文件扩展名（与后端支持的格式保持一致）
        val extension = audioFile.extension.lowercase()
        val validExtensions = listOf("wav", "mp3", "m4a", "ogg", "flac", "webm")
        
        if (extension !in validExtensions) {
            return "不支持的音频格式: .$extension（支持: ${validExtensions.joinToString(", ")}）"
        }
        
        return null // 验证通过
    }
}
