package com.example.mydiary.ui.drawing

import com.example.mydiary.data.models.DrawingMode
import com.example.mydiary.data.models.DrawingStyle
import com.example.mydiary.data.models.MediaItem
import java.time.Instant

data class SavedMediaItem(
    val type: String,
    val url: String,
    val timestamp: Instant = Instant.now(),
)

data class DrawingUiState(
    val mode: DrawingMode = DrawingMode.TEXT_TO_IMAGE,
    val prompt: String = "",
    val style: DrawingStyle = DrawingStyle.WATERCOLOR,
    val isGenerating: Boolean = false,
    val generatedImageUrl: String? = null,
    val promptUsed: String? = null,
    val error: String? = null,
    val isCreatingVideo: Boolean = false,
    val videoTaskId: String? = null,
    val videoStatus: String? = null,
    val videoUrl: String? = null,
    val videoError: String? = null,
    val diaryId: Long? = null,
    val mediaItems: List<MediaItem> = emptyList(),
)
