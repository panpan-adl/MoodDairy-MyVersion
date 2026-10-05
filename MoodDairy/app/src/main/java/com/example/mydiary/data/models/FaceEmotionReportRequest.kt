package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

/**
 * 摄像头表情识别上报请求体
 */
data class FaceEmotionReportRequest(
    @SerializedName("label") val label: String,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
