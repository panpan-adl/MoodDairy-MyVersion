package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

data class AgentTaskDraft(
    val title: String = "",
    val domain: String = "life",
    val minutes: Int = 25,
    @SerializedName("next_step") val nextStep: String = ""
)
data class AgentTask(
    val id: Long,
    val title: String,
    val domain: String,
    val minutes: Int,
    @SerializedName("next_step") val nextStep: String,
    @SerializedName("is_done") val isDone: Boolean,
    val date: String
)
data class AgentToday(val date: String, val tasks: List<AgentTask>)
data class AgentPlan(val suggestion: String, val tasks: List<AgentTaskDraft>)
data class AgentPlanRequest(
    val date: String,
    val record: String,
    val energy: String,
    @SerializedName("available_minutes") val availableMinutes: Int
)
data class AgentAdoptRequest(
    val date: String,
    val record: String,
    val energy: String,
    @SerializedName("available_minutes") val availableMinutes: Int,
    @SerializedName("request_id") val requestId: String,
    val tasks: List<AgentTaskDraft>
)
data class AgentTaskUpdate(
    val title: String? = null,
    val domain: String? = null,
    val minutes: Int? = null,
    @SerializedName("next_step") val nextStep: String? = null,
    @SerializedName("is_done") val isDone: Boolean? = null,
    val date: String? = null
)
data class AgentReviewRequest(val date: String, val reflection: String)
data class AgentReview(val content: String)
data class AgentSaveReview(
    val date: String,
    val content: String,
    @SerializedName("request_id") val requestId: String
)
data class AgentSavedDiary(@SerializedName("diary_id") val diaryId: Long)

