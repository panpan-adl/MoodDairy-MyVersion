package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

data class GrowthTrendPointResponse(
    @SerializedName("date")
    val date: String,
    @SerializedName("value")
    val value: Int,
    @SerializedName("label")
    val label: String? = null,
)

data class PositiveEventPointResponse(
    @SerializedName("diary_id")
    val diaryId: Long,
    @SerializedName("date")
    val date: String,
    @SerializedName("title")
    val title: String,
    @SerializedName("summary")
    val summary: String,
    @SerializedName("score")
    val score: Int,
)

data class GrowthPortraitResponse(
    @SerializedName("user_id")
    val userId: Long,
    @SerializedName("range_start")
    val rangeStart: String,
    @SerializedName("range_end")
    val rangeEnd: String,
    @SerializedName("emotion_curve")
    val emotionCurve: List<GrowthTrendPointResponse>,
    @SerializedName("stress_curve")
    val stressCurve: List<GrowthTrendPointResponse>,
    @SerializedName("positive_events")
    val positiveEvents: List<PositiveEventPointResponse>,
    @SerializedName("growth_keywords")
    val growthKeywords: List<String>,
    @SerializedName("multimodal_distribution")
    val multimodalDistribution: Map<String, Int>,
    @SerializedName("summary")
    val summary: String,
    @SerializedName("self_awareness_feedback")
    val selfAwarenessFeedback: String,
)

data class ModalityInsightResponse(
    @SerializedName("available")
    val available: Boolean,
    @SerializedName("emotion")
    val emotion: String? = null,
    @SerializedName("emotion_score")
    val emotionScore: Int? = null,
    @SerializedName("stress_score")
    val stressScore: Int? = null,
    @SerializedName("positive_event_score")
    val positiveEventScore: Int? = null,
    @SerializedName("confidence_score")
    val confidenceScore: Int? = null,
    @SerializedName("summary")
    val summary: String? = null,
    @SerializedName("evidence")
    val evidence: List<String> = emptyList(),
    @SerializedName("tags")
    val tags: List<String> = emptyList(),
)

data class FusionInsightResponse(
    @SerializedName("overall_emotion")
    val overallEmotion: String,
    @SerializedName("overall_emotion_score")
    val overallEmotionScore: Int,
    @SerializedName("stress_score")
    val stressScore: Int,
    @SerializedName("positive_event_score")
    val positiveEventScore: Int,
    @SerializedName("confidence_score")
    val confidenceScore: Int,
    @SerializedName("emotional_stability")
    val emotionalStability: String,
    @SerializedName("positive_events")
    val positiveEvents: List<String> = emptyList(),
    @SerializedName("stress_triggers")
    val stressTriggers: List<String> = emptyList(),
    @SerializedName("growth_keywords")
    val growthKeywords: List<String> = emptyList(),
    @SerializedName("explanation")
    val explanation: String,
)

data class DiaryMultimodalInsightResponse(
    @SerializedName("diary_id")
    val diaryId: Long,
    @SerializedName("diary_date")
    val diaryDate: String,
    @SerializedName("text_analysis")
    val textAnalysis: ModalityInsightResponse,
    @SerializedName("voice_analysis")
    val voiceAnalysis: ModalityInsightResponse,
    @SerializedName("image_analysis")
    val imageAnalysis: ModalityInsightResponse,
    @SerializedName("video_analysis")
    val videoAnalysis: ModalityInsightResponse,
    @SerializedName("drawing_analysis")
    val drawingAnalysis: ModalityInsightResponse,
    @SerializedName("fusion_analysis")
    val fusionAnalysis: FusionInsightResponse,
    @SerializedName("analysis_version")
    val analysisVersion: Int,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("updated_at")
    val updatedAt: String,
)
