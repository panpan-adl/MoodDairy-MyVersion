package com.example.mydiary.ui.drawing

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.DrawingMode
import com.example.mydiary.data.models.DrawingStyle
import com.example.mydiary.data.repository.DrawingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DrawingViewModel @Inject constructor(
    private val drawingRepository: DrawingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrawingUiState())
    val uiState: StateFlow<DrawingUiState> = _uiState.asStateFlow()

    private var sketchBitmap: Bitmap? = null
    private var sharedDrawingViewModel: SharedDrawingViewModel? = null

    fun bindSharedViewModel(sharedViewModel: SharedDrawingViewModel?) {
        sharedDrawingViewModel = sharedViewModel
    }

    fun setMode(mode: DrawingMode) {
        sharedDrawingViewModel?.setMode(mode) ?: _uiState.update { it.copy(mode = mode) }
    }

    fun setPrompt(prompt: String) {
        sharedDrawingViewModel?.setPrompt(prompt) ?: _uiState.update { it.copy(prompt = prompt) }
    }

    fun setStyle(style: DrawingStyle) {
        sharedDrawingViewModel?.setStyle(style) ?: _uiState.update { it.copy(style = style) }
    }

    fun setSketchBitmap(bitmap: Bitmap?) {
        sharedDrawingViewModel?.setSketchBitmap(bitmap) ?: run { sketchBitmap = bitmap }
    }

    fun setGeneratedImage(imageUrl: String) {
        sharedDrawingViewModel?.setGeneratedImage(imageUrl) ?: run {
            if (_uiState.value.generatedImageUrl == imageUrl) return
            _uiState.update {
                it.copy(
                    generatedImageUrl = imageUrl,
                    error = null,
                    videoError = null,
                )
            }
        }
    }

    fun showError(message: String) {
        sharedDrawingViewModel?.showError(message) ?: _uiState.update { it.copy(error = message) }
    }

    fun generateFromText() {
        sharedDrawingViewModel?.generateFromText() ?: run {
            val prompt = _uiState.value.prompt.trim()
            if (prompt.isBlank()) {
                showError("请输入画作描述")
                return
            }
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
    }

    fun enhanceSketch(imageDataUrl: String, prompt: String = _uiState.value.prompt) {
        sharedDrawingViewModel?.enhanceSketch(imageDataUrl, prompt) ?: run {
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
    }

    fun generateFromDiary(diaryId: Long) {
        sharedDrawingViewModel?.generateFromDiary(diaryId) ?: run {
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
    }
    fun createVideo(prompt: String = "画面缓缓动起来，自然流动") {
        sharedDrawingViewModel?.createVideo(prompt) ?: run {
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
    }

    fun createTransformVideo() {
        sharedDrawingViewModel?.createTransformVideo() ?: run {
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

    fun reset() {
        _uiState.update { DrawingUiState() }
        sketchBitmap = null
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, videoError = null) }
    }
}
