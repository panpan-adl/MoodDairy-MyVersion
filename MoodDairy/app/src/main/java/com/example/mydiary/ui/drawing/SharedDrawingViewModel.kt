package com.example.mydiary.ui.drawing

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.DrawingMode
import com.example.mydiary.data.models.DrawingStyle
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.DrawingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private const val TAG = "SharedDrawingVMDebug"

/**
 * 共享的 DrawingViewModel
 * 在导航子图 (diary_editor -> drawing -> drawing_result) 中共享
 * 管理绘画生成、视频生成状态，以及绘画结果的保存
 */
@HiltViewModel
class SharedDrawingViewModel @Inject constructor(
    private val drawingRepository: DrawingRepository,
    private val diaryRepository: DiaryRepository,
    private val drawingNavigationState: DrawingNavigationState
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrawingUiState())
    val uiState: StateFlow<DrawingUiState> = _uiState.asStateFlow()

    // 保存的媒体项（通过导航返回给日记编辑器）
    private val _savedMediaItems = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedMediaItems: StateFlow<List<SavedMediaItem>> = _savedMediaItems.asStateFlow()

    private var sketchBitmap: Bitmap? = null

    // 记录最近一次已自动跳转的生成图 URL，防止从结果页返回后重复触发导航
    private var lastAutoNavigatedImageUrl: String? = null

    fun setDiaryId(diaryId: Long?) {
        _uiState.update { it.copy(diaryId = diaryId) }
        if (diaryId != null) {
            viewModelScope.launch {
                refreshMediaItemsFromServer(diaryId)
            }
        } else {
            _uiState.update { it.copy(mediaItems = emptyList()) }
        }
    }

    /** 从服务端拉取当前日记媒体，避免自动保存时用空列表覆盖已有图片/视频 */
    private suspend fun refreshMediaItemsFromServer(diaryId: Long) {
        val result = diaryRepository.getDiaryById(diaryId).first { it !is ApiResult.Loading }
        if (result is ApiResult.Success) {
            _uiState.update { it.copy(mediaItems = result.data.mediaItems) }
            Log.d(TAG, "refreshMediaItemsFromServer: diaryId=$diaryId count=${result.data.mediaItems.size}")
        }
    }

    private suspend fun ensureMediaItemsForSync(): List<MediaItem> {
        val current = _uiState.value.mediaItems
        if (current.isNotEmpty()) {
            return current
        }
        val diaryId = _uiState.value.diaryId ?: return emptyList()
        val result = diaryRepository.getDiaryById(diaryId).first { it !is ApiResult.Loading }
        return if (result is ApiResult.Success) {
            result.data.mediaItems.also { items ->
                _uiState.update { it.copy(mediaItems = items) }
            }
        } else {
            emptyList()
        }
    }

    fun setMode(mode: DrawingMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun setPrompt(prompt: String) {
        _uiState.update { it.copy(prompt = prompt) }
    }

    fun setStyle(style: DrawingStyle) {
        _uiState.update { it.copy(style = style) }
    }

    fun setSketchBitmap(bitmap: Bitmap?) {
        sketchBitmap = bitmap
    }

    fun setGeneratedImage(imageUrl: String) {
        if (_uiState.value.generatedImageUrl == imageUrl) {
            return
        }
        _uiState.update {
            it.copy(
                generatedImageUrl = imageUrl,
                error = null,
                videoError = null,
                videoUrl = null,
                videoTaskId = null,
            )
        }
    }

    /**
     * 判断给定 URL 是否应该触发自动导航到结果页。
     * 当 URL 与上次已自动跳转的 URL 相同时返回 false，防止从结果页返回后重复触发。
     * 调用方应在本函数返回 true 后负责更新 lastAutoNavigatedImageUrl。
     */
    fun shouldAutoNavigateToDrawingResult(imageUrl: String): Boolean {
        if (lastAutoNavigatedImageUrl == imageUrl) {
            return false
        }
        lastAutoNavigatedImageUrl = imageUrl
        return true
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun generateFromText() {
        val prompt = _uiState.value.prompt.trim()
        if (prompt.isBlank()) {
            showError("请输入画作描述")
            return
        }
        lastAutoNavigatedImageUrl = null

        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            drawingRepository.generateDrawing(prompt)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            generatedImageUrl = response.imageUrl,
                            promptUsed = response.promptUsed,
                        )
                    }
                    autoSaveImageToDiary(response.imageUrl)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            error = error.message ?: "生成失败",
                        )
                    }
                }
        }
    }

    fun enhanceSketch(imageDataUrl: String, prompt: String = _uiState.value.prompt) {
        lastAutoNavigatedImageUrl = null
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            drawingRepository.enhanceSketch(
                imageUrl = imageDataUrl,
                prompt = prompt.trim(),
            ).onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generatedImageUrl = response.imageUrl,
                        promptUsed = response.promptUsed,
                    )
                }
                autoSaveImageToDiary(response.imageUrl)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = error.message ?: "增强失败",
                    )
                }
            }
        }
    }

    fun generateFromDiary(diaryId: Long) {
        lastAutoNavigatedImageUrl = null
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            drawingRepository.generateFromDiary(diaryId, _uiState.value.style)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            generatedImageUrl = response.imageUrl,
                            promptUsed = response.promptUsed,
                        )
                    }
                    autoSaveImageToDiary(response.imageUrl)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            error = error.message ?: "生成失败",
                        )
                    }
                }
        }
    }

    fun createVideo(prompt: String = "画面缓缓动起来，自然流动") {
        val imageUrl = _uiState.value.generatedImageUrl ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingVideo = true, videoError = null) }
            drawingRepository.createVideo(imageUrl, prompt)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            videoTaskId = response.taskId,
                            videoStatus = response.status,
                        )
                    }
                    pollVideoStatus(response.taskId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCreatingVideo = false,
                            videoError = error.message,
                        )
                    }
                }
        }
    }

    fun createTransformVideo() {
        val imageUrl = _uiState.value.generatedImageUrl ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingVideo = true, videoError = null) }
            drawingRepository.createTransformVideo(imageUrl)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            videoTaskId = response.taskId,
                            videoStatus = response.status,
                        )
                    }
                    pollVideoStatus(response.taskId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCreatingVideo = false,
                            videoError = error.message,
                        )
                    }
                }
        }
    }

    private fun pollVideoStatus(taskId: String) {
        viewModelScope.launch {
            drawingRepository.waitForVideo(taskId)
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            isCreatingVideo = false,
                            videoStatus = status.status,
                            videoUrl = status.videoUrl,
                            videoError = if (!status.isSuccess) status.error else null,
                        )
                    }
                    if (status.isSuccess && status.videoUrl != null) {
                        autoSaveVideoToDiary(status.videoUrl)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCreatingVideo = false,
                            videoError = error.message,
                        )
                    }
                }
        }
    }

    /**
     * 将 AI 生成的图片追加到日记媒体列表并同步到服务端。
     * 若 diaryId 为 null 则跳过（无日记上下文）。
     * 若该 URL 已存在则忽略，避免重复条目。
     * 完成后通知编辑器刷新。
     */
    private fun autoSaveImageToDiary(imageUrl: String) {
        val diaryId = _uiState.value.diaryId ?: return
        viewModelScope.launch {
            try {
                val existingItems = ensureMediaItemsForSync()
                if (existingItems.any { it is MediaItem.Image && it.url == imageUrl }) {
                    Log.d(TAG, "autoSaveImageToDiary: 图片 URL 已存在，跳过: $imageUrl")
                    return@launch
                }
                val newItem = MediaItem.Image(
                    id = "drawing_${System.currentTimeMillis()}",
                    assetId = null,
                    url = imageUrl,
                    thumbnailUrl = null,
                    order = existingItems.size,
                    createdAt = Instant.now()
                )
                val updatedItems = existingItems + newItem
                val result = diaryRepository.syncMediaItems(diaryId, updatedItems)
                    .first { it !is ApiResult.Loading }
                if (result is ApiResult.Success) {
                    _uiState.update { it.copy(mediaItems = result.data) }
                    Log.d(TAG, "autoSaveImageToDiary: 图片已保存到日记 diaryId=$diaryId, url=$imageUrl")
                } else {
                    Log.e(TAG, "autoSaveImageToDiary: 保存失败 $result")
                }
                drawingNavigationState.emitDiaryMediaRefresh(diaryId)
            } catch (e: Exception) {
                Log.e(TAG, "autoSaveImageToDiary: 异常 $e", e)
            }
        }
    }

    /**
     * 将 AI 生成的视频追加到日记媒体列表并同步到服务端。
     * 语义与结果页"保存视频到日记"一致。
     */
    private fun autoSaveVideoToDiary(videoUrl: String) {
        val diaryId = _uiState.value.diaryId ?: return
        viewModelScope.launch {
            try {
                val existingItems = ensureMediaItemsForSync()
                if (existingItems.any { it is MediaItem.Video && it.url == videoUrl }) {
                    Log.d(TAG, "autoSaveVideoToDiary: 视频 URL 已存在，跳过: $videoUrl")
                    return@launch
                }
                val newItem = MediaItem.Video(
                    id = "drawing_${System.currentTimeMillis()}",
                    assetId = null,
                    url = videoUrl,
                    thumbnailUrl = null,
                    duration = 0,
                    order = existingItems.size,
                    createdAt = Instant.now()
                )
                val updatedItems = existingItems + newItem
                val result = diaryRepository.syncMediaItems(diaryId, updatedItems)
                    .first { it !is ApiResult.Loading }
                if (result is ApiResult.Success) {
                    _uiState.update { it.copy(mediaItems = result.data) }
                    Log.d(TAG, "autoSaveVideoToDiary: 视频已保存到日记 diaryId=$diaryId, url=$videoUrl")
                } else {
                    Log.e(TAG, "autoSaveVideoToDiary: 保存失败 $result")
                }
                drawingNavigationState.emitDiaryMediaRefresh(diaryId)
            } catch (e: Exception) {
                Log.e(TAG, "autoSaveVideoToDiary: 异常 $e", e)
            }
        }
    }

    /**
     * 请求保存图片到日记
     */
    fun requestSaveImageToDiary() {
        val imageUrl = _uiState.value.generatedImageUrl ?: return
        _savedMediaItems.update {
            it + SavedMediaItem(type = "image", url = imageUrl)
        }
    }

    /**
     * 请求保存视频到日记
     */
    fun requestSaveVideoToDiary() {
        val videoUrl = _uiState.value.videoUrl ?: return
        _savedMediaItems.update {
            it + SavedMediaItem(type = "video", url = videoUrl)
        }
    }

    /**
     * 是否有待保存的媒体
     */
    fun hasPendingMedia(): Boolean {
        return _savedMediaItems.value.isNotEmpty()
    }

    /**
     * 获取待保存的媒体（消费）
     */
    fun consumeSavedMediaItems(): List<SavedMediaItem> {
        val items = _savedMediaItems.value
        _savedMediaItems.value = emptyList()
        return items
    }

    /**
     * 获取待保存的媒体（不消费）
     */
    fun getPendingMedia(): List<SavedMediaItem> {
        return _savedMediaItems.value
    }

    /**
     * 清除待保存的媒体
     */
    fun clearSavedMediaItems() {
        _savedMediaItems.value = emptyList()
    }

    fun reset() {
        _uiState.update { DrawingUiState(diaryId = _uiState.value.diaryId) }
        sketchBitmap = null
        lastAutoNavigatedImageUrl = null
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, videoError = null) }
    }
}
