package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

const val DEFAULT_DRAWING_SIZE = "2048x2048"

data class DrawingGenerateRequest(
    val prompt: String,
    val size: String = DEFAULT_DRAWING_SIZE,
    val watermark: Boolean = false,
)

data class DrawingEnhanceRequest(
    @SerializedName("image_url") val imageUrl: String,
    val prompt: String = "",
    val size: String = DEFAULT_DRAWING_SIZE,
    val watermark: Boolean = false,
)

data class SketchUploadResponse(
    @SerializedName("image_url") val imageUrl: String,
    val message: String,
)

data class DrawingFromDiaryRequest(
    val style: String = "watercolor",
    @SerializedName("include_emotion_transform") val includeEmotionTransform: Boolean = true,
)

data class DrawingResponse(
    @SerializedName("image_url") val imageUrl: String,
    @SerializedName("prompt_used") val promptUsed: String,
    val message: String = "Drawing generated successfully",
)

data class VideoGenerateRequest(
    @SerializedName("image_url") val imageUrl: String,
    val prompt: String,
    val duration: Int = 5,
    @SerializedName("camera_fixed") val cameraFixed: Boolean = false,
    val watermark: Boolean = true,
)

data class VideoTransformRequest(
    @SerializedName("image_url") val imageUrl: String,
    @SerializedName("transformation_type") val transformationType: String = "negative_to_positive",
    val duration: Int = 5,
)

data class VideoTaskResponse(
    @SerializedName("task_id") val taskId: String,
    val status: String,
    val message: String = "Video task created",
)

data class VideoStatusResponse(
    @SerializedName("task_id") val taskId: String,
    val status: String,
    @SerializedName("video_url") val videoUrl: String? = null,
    val error: String? = null,
) {
    val isComplete: Boolean
        get() = status == "succeeded" || status == "failed"

    val isSuccess: Boolean
        get() = status == "succeeded"
}

enum class DrawingMode {
    TEXT_TO_IMAGE,
    SKETCH_ENHANCE,
    DIARY_TO_IMAGE,
}

enum class DrawingStyle(val displayName: String, val apiName: String) {
    WATERCOLOR("水彩", "watercolor"),
    OIL("油画", "oil"),
    CARTOON("卡通", "cartoon"),
    REALISTIC("写实", "realistic"),
}

// ========== SD 开关相关 ==========

data class SDEnabledResponse(
    val sd_enabled: Boolean,
    val current_mode: String,
    val available_modes: List<AvailableMode>,
    val message: String,
)

data class AvailableMode(
    val value: String,
    val name: String,
    val description: String,
)

data class SDEnabledRequest(
    val sd_enabled: Boolean,
)

// ========== AI 绘图显示控制相关 ==========

data class DrawingEnabledResponse(
    val drawing_enabled: Boolean,
    val message: String,
)
