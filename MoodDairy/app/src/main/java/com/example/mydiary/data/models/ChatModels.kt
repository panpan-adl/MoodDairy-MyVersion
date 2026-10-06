package com.example.mydiary.data.models

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val content: String,
    val isFromUser: Boolean,
    @SerializedName("image_uris")
    val imageUris: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    @SerializedName("social_search_results")
    val socialSearchResults: List<SocialSearchItem>? = null,
    @SerializedName("social_search_keyword")
    val socialSearchKeyword: String? = null,
    /** 搜索功能被锁定（未绑定密钥/余额不足），true 时显示引导卡片 */
    @SerializedName("social_search_locked")
    val socialSearchLocked: Boolean = false,
    /** 锁定原因：NO_KEY=未绑定密钥，NO_BALANCE=余额不足 */
    @SerializedName("social_search_error_code")
    val socialSearchErrorCode: String? = null,
)

data class ChatContext(
    @SerializedName("diary_summaries")
    val diarySummaries: List<DiarySummarySimple> = emptyList(),
    @SerializedName("image_data_urls")
    val imageDataUrls: List<String> = emptyList(),
)

data class ChatConfirmation(
    @SerializedName("plan_id")
    val planId: String,
    @SerializedName("approved")
    val approved: Boolean,
    @SerializedName("edited_arguments")
    val editedArguments: JsonObject? = null,
)

data class ChatRequest(
    @SerializedName("user_id")
    val userId: Long,
    @SerializedName("message")
    val message: String,
    @SerializedName("conversation_id")
    val conversationId: String,
    @SerializedName("context")
    val context: ChatContext = ChatContext(),
    @SerializedName("confirmation")
    val confirmation: ChatConfirmation? = null,
)

data class ChatResponse(
    @SerializedName("reply")
    val reply: String,
    @SerializedName("timestamp")
    val timestamp: String,
)

data class ChatStreamEvent(
    @SerializedName("type")
    val type: String,
    @SerializedName("delta")
    val delta: String? = null,
    @SerializedName("reply")
    val reply: String? = null,
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("timestamp")
    val timestamp: String? = null,
    @SerializedName("plan_id")
    val planId: String? = null,
    @SerializedName("tool")
    val tool: String? = null,
    @SerializedName("mode")
    val mode: String? = null,
    @SerializedName("arguments")
    val arguments: JsonObject? = null,
    @SerializedName("requires_confirmation")
    val requiresConfirmation: Boolean? = null,
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("preview")
    val preview: String? = null,
    @SerializedName("expires_at")
    val expiresAt: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("ok")
    val ok: Boolean? = null,
    @SerializedName("result")
    val result: JsonObject? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    @SerializedName("action")
    val action: String? = null,
    @SerializedName("payload")
    val payload: JsonObject? = null,
    @SerializedName("final_state")
    val finalState: String? = null,
    @SerializedName("error_code")
    val errorCode: String? = null,
    @SerializedName("error_message")
    val errorMessage: String? = null,
    @SerializedName("emotion_state")
    val emotionState: String? = null,
    @SerializedName("emotion_type")
    val emotionType: String? = null,
    @SerializedName("emotion_score")
    val emotionScore: Int? = null,
    @SerializedName("trigger_reason")
    val triggerReason: String? = null,
    @SerializedName("is_crisis")
    val isCrisis: Boolean? = null,
    @SerializedName("bundle_id")
    val bundleId: String? = null,
    @SerializedName("message")
    val bundleMessage: String? = null,
    @SerializedName("services")
    val services: List<ChatServiceAction>? = null,
    @SerializedName("auto_actions")
    val autoActions: List<ChatServiceAction>? = null,
    @SerializedName("keyword")
    val keyword: String? = null,
    @SerializedName("platform")
    val platform: String? = null,
    @SerializedName("items")
    val items: List<SocialSearchItem>? = null,
    @SerializedName("locked")
    val locked: Boolean? = null,
)

data class ChatServiceAction(
    @SerializedName("id")
    val id: String,
    @SerializedName("title")
    val title: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("action")
    val action: String,
    @SerializedName("payload")
    val payload: JsonObject? = null,
    @SerializedName("auto_start")
    val autoStart: Boolean = false,
)

data class SocialSearchItem(
    @SerializedName("platform")
    val platform: String,
    @SerializedName("note_id")
    val noteId: String,
    @SerializedName("title")
    val title: String,
    @SerializedName("description")
    val description: String = "",
    @SerializedName("cover_url")
    val coverUrl: String? = null,
    @SerializedName("url")
    val url: String,
    @SerializedName("author_name")
    val authorName: String? = null,
    @SerializedName("like_count")
    val likeCount: Int = 0,
    @SerializedName("content_type")
    val contentType: String? = null,
)

data class DiarySummarySimple(
    @SerializedName("diary_id")
    val diaryId: Long,
    @SerializedName("diary_date")
    val diaryDate: String,
    @SerializedName("summary")
    val summary: String,
    @SerializedName("keywords")
    val keywords: List<String>,
    @SerializedName("primary_emotion")
    val primaryEmotion: String,
    @SerializedName("emotion_score")
    val emotionScore: Int,
)

/** 社交搜索（TikHub）账户开通状态 */
data class SocialSearchStatus(
    @SerializedName("configured")
    val configured: Boolean = false,
    @SerializedName("bound")
    val bound: Boolean = false,
    @SerializedName("unlocked")
    val unlocked: Boolean = false,
    @SerializedName("balance")
    val balance: Double = 0.0,
    @SerializedName("free_credit")
    val freeCredit: Double = 0.0,
    @SerializedName("total")
    val total: Double = 0.0,
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("key_status")
    val keyStatus: String? = null,
    @SerializedName("billing_url")
    val billingUrl: String? = null,
    @SerializedName("register_url")
    val registerUrl: String? = null,
    @SerializedName("api_keys_url")
    val apiKeysUrl: String? = null,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("error_code")
    val errorCode: String? = null,
)

data class BindTikHubKeyRequest(
    @SerializedName("api_key")
    val apiKey: String,
)
