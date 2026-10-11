package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

data class FaceEmotionReportRequest(
    @SerializedName("emotion")
    val emotion: String,

    @SerializedName("confidence")
    val confidence: Float,

    @SerializedName("captured_at")
    val capturedAt: String, // ISO-8601 格式
    @SerializedName("sustained_negative_ms")
    val sustainedNegativeMs: Long = 0L,
    @SerializedName("facial_expression")
    val facialExpression: String? = null,
)
