package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

/**
 * 摄像头表情识别上报响应体
 */
data class FaceEmotionReportResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)
