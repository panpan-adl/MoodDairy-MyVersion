package com.example.mydiary.data.repository

import com.example.mydiary.data.models.DEFAULT_DRAWING_SIZE
import com.example.mydiary.data.models.DrawingEnhanceRequest
import com.example.mydiary.data.models.DrawingFromDiaryRequest
import com.example.mydiary.data.models.DrawingEnabledResponse
import com.example.mydiary.data.models.DrawingGenerateRequest
import com.example.mydiary.data.models.DrawingResponse
import com.example.mydiary.data.models.DrawingStyle
import com.example.mydiary.data.models.SDEnabledRequest
import com.example.mydiary.data.models.SDEnabledResponse
import com.example.mydiary.data.models.SketchUploadResponse
import com.example.mydiary.data.models.VideoGenerateRequest
import com.example.mydiary.data.models.VideoStatusResponse
import com.example.mydiary.data.models.VideoTaskResponse
import com.example.mydiary.data.models.VideoTransformRequest
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DrawingRepository @Inject constructor(
    @Named("longTimeout") private val apiService: DiaryApiService,
) {

    suspend fun generateDrawing(
        prompt: String,
        size: String = DEFAULT_DRAWING_SIZE,
        watermark: Boolean = false,
    ): Result<DrawingResponse> {
        val request = DrawingGenerateRequest(
            prompt = prompt,
            size = size,
            watermark = watermark,
        )
        val apiCall: suspend () -> retrofit2.Response<DrawingResponse> = {
            apiService.generateDrawing(request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun uploadSketch(file: File): Result<String> {
        val mediaType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val filePart = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody(mediaType.toMediaTypeOrNull()),
        )
        val apiCall: suspend () -> retrofit2.Response<SketchUploadResponse> = {
            apiService.uploadSketch(filePart)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data.imageUrl)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun enhanceSketch(
        imageUrl: String,
        prompt: String,
        size: String = DEFAULT_DRAWING_SIZE,
        watermark: Boolean = false,
    ): Result<DrawingResponse> {
        val request = DrawingEnhanceRequest(
            imageUrl = imageUrl,
            prompt = prompt,
            size = size,
            watermark = watermark,
        )
        val apiCall: suspend () -> retrofit2.Response<DrawingResponse> = {
            apiService.enhanceSketch(request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun generateFromDiary(
        diaryId: Long,
        style: DrawingStyle = DrawingStyle.WATERCOLOR,
        includeEmotionTransform: Boolean = true,
    ): Result<DrawingResponse> {
        val request = DrawingFromDiaryRequest(
            style = style.apiName,
            includeEmotionTransform = includeEmotionTransform,
        )
        val apiCall: suspend () -> retrofit2.Response<DrawingResponse> = {
            apiService.generateFromDiary(diaryId, request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun createVideo(
        imageUrl: String,
        prompt: String,
        duration: Int = 5,
        cameraFixed: Boolean = false,
    ): Result<VideoTaskResponse> {
        val request = VideoGenerateRequest(
            imageUrl = imageUrl,
            prompt = prompt,
            duration = duration,
            cameraFixed = cameraFixed,
        )
        val apiCall: suspend () -> retrofit2.Response<VideoTaskResponse> = {
            apiService.createVideo(request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun createTransformVideo(
        imageUrl: String,
        transformationType: String = "negative_to_positive",
        duration: Int = 5,
    ): Result<VideoTaskResponse> {
        val request = VideoTransformRequest(
            imageUrl = imageUrl,
            transformationType = transformationType,
            duration = duration,
        )
        val apiCall: suspend () -> retrofit2.Response<VideoTaskResponse> = {
            apiService.createTransformVideo(request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun getVideoStatus(taskId: String): Result<VideoStatusResponse> {
        val apiCall: suspend () -> retrofit2.Response<VideoStatusResponse> = {
            apiService.getVideoStatus(taskId)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun waitForVideo(
        taskId: String,
        maxWaitSeconds: Int = 300,
        pollIntervalMs: Long = 5000,
    ): Result<VideoStatusResponse> {
        val startTime = System.currentTimeMillis()
        val maxWaitMs = maxWaitSeconds * 1000L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val result = getVideoStatus(taskId)
            result.onSuccess { status ->
                if (status.isComplete) {
                    return Result.success(status)
                }
            }
            result.onFailure { return Result.failure(it) }
            delay(pollIntervalMs)
        }

        return Result.failure(Exception("Video generation timed out"))
    }

    suspend fun getSDEnabled(): Result<SDEnabledResponse> {
        val request = SDEnabledRequest(sd_enabled = true)
        val apiCall: suspend () -> retrofit2.Response<SDEnabledResponse> = {
            apiService.getSDEnabled()
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun setSDEnabled(enabled: Boolean): Result<SDEnabledResponse> {
        val request = SDEnabledRequest(sd_enabled = enabled)
        val apiCall: suspend () -> retrofit2.Response<SDEnabledResponse> = {
            apiService.setSDEnabled(request)
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }

    suspend fun getDrawingEnabled(): Result<DrawingEnabledResponse> {
        val apiCall: suspend () -> retrofit2.Response<DrawingEnabledResponse> = {
            apiService.getDrawingEnabled()
        }
        val result = SafeApiCall.execute(apiCall)
        return when (result) {
            is ApiResult.Success -> Result.success(result.data)
            is ApiResult.Error -> Result.failure(Exception(result.message))
            is ApiResult.Loading -> Result.failure(Exception("Unexpected loading state"))
        }
    }
}
