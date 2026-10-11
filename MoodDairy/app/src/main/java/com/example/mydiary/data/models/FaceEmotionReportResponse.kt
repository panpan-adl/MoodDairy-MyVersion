package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

data class FaceEmotionReportResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String? = null,
    @SerializedName("service_bundle")
    val serviceBundle: ChatStreamEvent? = null,
)
