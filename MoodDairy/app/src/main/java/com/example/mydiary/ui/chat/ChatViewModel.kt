package com.example.mydiary.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.capture.EmotionCaptureController
import com.example.mydiary.data.local.ChatHistoryStorage
import com.example.mydiary.data.local.EmotionCapturePrefs
import com.example.mydiary.data.models.ChatConfirmation
import com.example.mydiary.data.models.ChatContext
import com.example.mydiary.data.models.ChatMessage
import com.example.mydiary.data.models.ChatRequest
import com.example.mydiary.data.models.ChatServiceAction
import com.example.mydiary.data.models.ChatStreamEvent
import com.example.mydiary.data.models.DiarySummarySimple
import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import com.example.mydiary.data.repository.DrawingRepository
import com.example.mydiary.data.repository.EmotionSignalRepository
import com.example.mydiary.data.repository.VoiceRepository
import com.example.mydiary.di.AuthInterceptor
import com.example.mydiary.music.MusicWidgetController
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Named

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiService: DiaryApiService,
    private val chatHistoryStorage: ChatHistoryStorage,
    private val drawingRepository: DrawingRepository,
    private val voiceRepository: VoiceRepository,
    private val emotionCaptureController: EmotionCaptureController,
    private val emotionSignalRepository: EmotionSignalRepository,
    private val emotionCapturePrefs: EmotionCapturePrefs,
    private val musicWidgetController: MusicWidgetController,
    @Named("shortTimeout") private val okHttpClient: OkHttpClient,
    private val authInterceptor: AuthInterceptor,
    private val gson: Gson,
) : ViewModel() {

    private val tag = "ChatViewModel"

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _diarySummaries = MutableStateFlow<List<DiarySummarySimple>>(emptyList())
    val diarySummaries: StateFlow<List<DiarySummarySimple>> = _diarySummaries.asStateFlow()

    private val _pendingPlans = MutableStateFlow<List<PendingToolPlanUi>>(emptyList())
    val pendingPlans: StateFlow<List<PendingToolPlanUi>> = _pendingPlans.asStateFlow()

    private val _serviceBundles = MutableStateFlow<List<EmotionServiceBundleUi>>(emptyList())
    val serviceBundles: StateFlow<List<EmotionServiceBundleUi>> = _serviceBundles.asStateFlow()

    private val _clientActions = MutableSharedFlow<ChatClientAction>(extraBufferCapacity = 8)
    val clientActions: SharedFlow<ChatClientAction> = _clientActions.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ============================================================================
    // 摄像头表情识别 (Face Emotion Capture)
    // ============================================================================

    /** 用户是否已授权表情识别；null 表示尚未询问过 */
    val emotionConsentGranted: StateFlow<Boolean?> = emotionCapturePrefs.consentGranted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 表情识别功能开关 */
    val emotionCaptureEnabled: StateFlow<Boolean> = emotionCapturePrefs.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 是否需要弹出授权弹窗（DataStore 加载完成且尚未询问过时为 true，避免初始帧闪弹窗） */
    val shouldAskEmotionConsent: StateFlow<Boolean> = emotionCapturePrefs.consentGranted
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isEmotionCapturing = MutableStateFlow(false)
    val isEmotionCapturing: StateFlow<Boolean> = _isEmotionCapturing.asStateFlow()

    private var userId: Long = 0L
    private var conversationId: String = ""

    fun initializeChat(userId: Long) {
        this.userId = userId
        if (conversationId.isBlank()) {
            conversationId = "conv_${userId}_${UUID.randomUUID().toString().take(8)}"
        }

        viewModelScope.launch {
            val savedMessages = chatHistoryStorage.loadMessages(userId)
            if (savedMessages.isNotEmpty()) {
                _messages.value = savedMessages
            } else {
                addMessage(
                    ChatMessage(
                        content = "你好，我是你的日记助手。你可以发文字、图片或语音给我。",
                        isFromUser = false,
                    ),
                )
            }
            fetchRecentSummaries()
        }
    }

    private fun fetchRecentSummaries() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            when (val result = SafeApiCall.execute { apiService.getRecentSummaries(userId, 10) }) {
                is ApiResult.Success -> {
                    _diarySummaries.value = result.data
                    Log.d(tag, "summaries loaded: ${result.data.size}")
                }
                is ApiResult.Error -> {
                    _errorMessage.value = "获取日记摘要失败: ${result.message}"
                }
                is ApiResult.Loading -> Unit
            }

            _isLoading.value = false
        }
    }

    fun sendMessage(
        content: String,
        imageUris: List<String> = emptyList(),
        imagePreviewUris: List<String> = emptyList(),
    ) {
        if (content.isBlank() && imageUris.isEmpty()) {
            return
        }

        val trimmedContent = content.trim()
        val requestMessage = if (trimmedContent.isNotBlank()) trimmedContent else "Please help me look at this image."

        addMessage(
            ChatMessage(
                content = trimmedContent,
                isFromUser = true,
                imageUris = imagePreviewUris,
            ),
        )

        val assistantTimestamp = System.currentTimeMillis() + 1
        addMessage(
            ChatMessage(
                content = "",
                isFromUser = false,
                timestamp = assistantTimestamp,
                isStreaming = true,
            ),
        )

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val uploadedImageUrls = when (val uploaded = uploadImagesForChat(imageUris)) {
                is ApiResult.Success -> uploaded.data
                is ApiResult.Error -> {
                    _errorMessage.value = "图片上传失败: ${uploaded.message}"
                    replaceAssistantMessage(
                        timestamp = assistantTimestamp,
                        content = "图片发送失败，请稍后重试。",
                        isStreaming = false,
                    )
                    _isLoading.value = false
                    return@launch
                }
                is ApiResult.Loading -> emptyList()
            }

            val request = ChatRequest(
                userId = userId,
                message = requestMessage,
                conversationId = conversationId,
                context = ChatContext(
                    diarySummaries = _diarySummaries.value,
                    imageDataUrls = uploadedImageUrls,
                ),
                confirmation = null,
            )

            try {
                streamReply(request, assistantTimestamp)
            } catch (error: Exception) {
                Log.e(tag, "sendMessage failed", error)
                _errorMessage.value = "聊天请求异常: ${error.message}"
                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = "抱歉，当前无法回复，请稍后再试。",
                    isStreaming = false,
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun submitPlanConfirmation(
        planId: String,
        approved: Boolean,
        editedArgumentsJson: String? = null,
    ) {
        if (_isLoading.value || userId == 0L || planId.isBlank()) {
            return
        }

        val editedArguments = if (approved && !editedArgumentsJson.isNullOrBlank()) {
            try {
                JsonParser().parse(editedArgumentsJson).asJsonObject
            } catch (error: Exception) {
                _errorMessage.value = "编辑参数不是合法 JSON: ${error.message}"
                return
            }
        } else {
            null
        }

        _pendingPlans.value = _pendingPlans.value.filterNot { it.planId == planId }

        addMessage(
            ChatMessage(
                content = if (approved) "确认执行操作" else "取消操作",
                isFromUser = true,
            ),
        )

        val assistantTimestamp = System.currentTimeMillis() + 1
        addMessage(
            ChatMessage(
                content = "",
                isFromUser = false,
                timestamp = assistantTimestamp,
                isStreaming = true,
            ),
        )

        val request = ChatRequest(
            userId = userId,
            message = "",
            conversationId = conversationId,
            context = ChatContext(
                diarySummaries = _diarySummaries.value,
                imageDataUrls = emptyList(),
            ),
            confirmation = ChatConfirmation(
                planId = planId,
                approved = approved,
                editedArguments = editedArguments,
            ),
        )

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                streamReply(request, assistantTimestamp)
            } catch (error: Exception) {
                Log.e(tag, "submitPlanConfirmation failed", error)
                _errorMessage.value = "确认请求异常: ${error.message}"
                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = "确认执行失败，请稍后再试。",
                    isStreaming = false,
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendVoiceMessage(audioUri: String) {
        if (audioUri.isBlank() || _isLoading.value) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val transcribeResult = transcribeAudioFromUri(audioUri)
            _isLoading.value = false

            transcribeResult
                .onSuccess { text ->
                    val transcript = text.trim()
                    if (transcript.isBlank()) {
                        _errorMessage.value = "语音转写结果为空"
                        return@onSuccess
                    }
                    sendMessage(content = transcript)
                }
                .onFailure { error ->
                    _errorMessage.value = "语音转写失败: ${error.message ?: "未知错误"}"
                }
        }
    }

    fun performServiceAction(action: EmotionServiceActionUi) {
        when (action.action) {
            "continue_chat" -> {
                addMessage(
                    ChatMessage(
                        content = "我在这里，我们可以慢慢说。你愿意从刚才最难受的那一刻讲起吗？",
                        isFromUser = false,
                    ),
                )
            }
            "open_music" -> {
                // 音乐弹窗/悬浮条由全局控制器管理，跨页面存活
                musicWidgetController.openRecommendations(action.payload["mood"].orEmpty())
            }
            else -> {
                _clientActions.tryEmit(
                    ChatClientAction(
                        action = action.action,
                        payload = action.payload,
                    ),
                )
            }
        }
    }

    fun dismissServiceBundle(bundleId: String) {
        _serviceBundles.value = _serviceBundles.value.filterNot { it.bundleId == bundleId }
    }

    private suspend fun streamReply(request: ChatRequest, assistantTimestamp: Long) {
        withContext(Dispatchers.IO) {
            // 去掉日志等拦截器以免干扰 SSE；保留 JWT 认证
            // AI 生成较慢（可能超过 1 分钟），超时放宽到 180 秒，配合后端心跳保活
            val streamingClient = okHttpClient.newBuilder().apply {
                interceptors().clear()
                networkInterceptors().clear()
                addInterceptor(authInterceptor)
                connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            }.build()

            val body = gson.toJson(request)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiEndpoints.apiBaseUrl}chat/stream")
                .post(body)
                .header("Accept", "text/event-stream")
                .build()

            streamingClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw IllegalStateException(
                        if (errorBody.isBlank()) "HTTP ${response.code}" else "HTTP ${response.code}: $errorBody",
                    )
                }

                val source = response.body?.source()
                    ?: throw IllegalStateException("Empty streaming response body")

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) {
                        continue
                    }

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank()) {
                        continue
                    }

                    val event = gson.fromJson(payload, ChatStreamEvent::class.java)
                    handleStreamEvent(assistantTimestamp, event)
                }
            }
        }
    }

    private fun handleServiceBundleEvent(event: ChatStreamEvent) {
        val bundleId = event.bundleId.orEmpty()
        if (bundleId.isBlank()) return

        val services = event.services.orEmpty().map { it.toUi() }
        if (services.isEmpty()) return

        val bundle = EmotionServiceBundleUi(
            bundleId = bundleId,
            title = event.title ?: "情绪关怀",
            message = event.bundleMessage ?: "最近情绪有些低落，我为你准备了几件小事。",
            triggerReason = event.triggerReason.orEmpty(),
            isCrisis = event.isCrisis == true,
            services = services,
        )
        _serviceBundles.value = (_serviceBundles.value.filterNot { it.bundleId == bundleId } + bundle)

        event.autoActions.orEmpty()
            .filter { it.autoStart }
            .forEach { autoAction ->
                _clientActions.tryEmit(
                    ChatClientAction(
                        action = autoAction.action,
                        payload = autoAction.payload.toStringMap(),
                    ),
                )
            }
    }

    private fun ChatServiceAction.toUi(): EmotionServiceActionUi {
        return EmotionServiceActionUi(
            id = id,
            title = title,
            description = description,
            action = action,
            payload = payload.toStringMap(),
            autoStart = autoStart,
        )
    }

    private fun handleStreamEvent(assistantTimestamp: Long, event: ChatStreamEvent) {
        when (event.type) {
            "start" -> Unit
            "delta" -> {
                val delta = event.delta.orEmpty()
                if (delta.isNotEmpty()) {
                    appendAssistantDelta(assistantTimestamp, delta)
                }
            }
            "tool_plan" -> {
                val planId = event.planId.orEmpty()
                if (planId.isNotBlank()) {
                    val pending = PendingToolPlanUi(
                        planId = planId,
                        tool = event.tool.orEmpty(),
                        title = event.title ?: event.tool.orEmpty(),
                        preview = event.preview.orEmpty(),
                        argumentsJson = event.arguments?.toString() ?: "{}",
                        expiresAt = event.expiresAt,
                        requiresConfirmation = event.requiresConfirmation != false,
                    )
                    _pendingPlans.value = (_pendingPlans.value.filterNot { it.planId == pending.planId } + pending)
                }
                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = "已生成待确认操作，请确认或编辑后执行。",
                    isStreaming = false,
                )
            }
            "awaiting_confirmation" -> {
                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = "等待你的确认。",
                    isStreaming = false,
                )
            }
            "tool_status" -> {
                // 过程态不写入气泡，最终由 done 中的 reply 展示（如 **完成**）
            }
            "tool_result" -> {
                // 同上，避免 [工具结果] 等调试信息出现在聊天区
            }
            "emotion_signal" -> {
                Log.d(
                    tag,
                    "emotion signal: state=${event.emotionState}, type=${event.emotionType}, score=${event.emotionScore}, reason=${event.triggerReason}",
                )
            }
            "service_bundle" -> {
                handleServiceBundleEvent(event)
            }
            "client_action" -> {
                val action = event.action.orEmpty()
                val payload = event.payload.toStringMap()
                _clientActions.tryEmit(ChatClientAction(action = action, payload = payload))
            }
            "done" -> {
                val finalState = event.finalState.orEmpty().lowercase()
                if (finalState == "error") {
                    _errorMessage.value = event.errorMessage ?: event.errorCode ?: "请求失败"
                }

                val finalReply = event.reply
                    ?: _messages.value.firstOrNull { it.timestamp == assistantTimestamp }?.content
                    ?: ""

                val fallback = if (finalState == "error") {
                    "处理失败，请重试。"
                } else {
                    "好的。"
                }

                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = finalReply.ifBlank { fallback },
                    isStreaming = false,
                )
            }
            "error" -> {
                _errorMessage.value = event.error ?: "聊天流式输出失败"
                replaceAssistantMessage(
                    timestamp = assistantTimestamp,
                    content = "抱歉，当前无法回复，请稍后再试。",
                    isStreaming = false,
                )
            }
        }
    }

    private fun addMessage(message: ChatMessage) {
        updateMessages(_messages.value + message)
    }

    private fun appendAssistantDelta(timestamp: Long, delta: String) {
        updateMessages(
            _messages.value.map { message ->
                if (message.timestamp == timestamp) {
                    message.copy(content = message.content + delta, isStreaming = true)
                } else {
                    message
                }
            },
        )
    }

    private fun replaceAssistantMessage(
        timestamp: Long,
        content: String,
        isStreaming: Boolean,
    ) {
        updateMessages(
            _messages.value.map { message ->
                if (message.timestamp == timestamp) {
                    message.copy(content = content, isStreaming = isStreaming)
                } else {
                    message
                }
            },
        )
    }

    private fun updateMessages(messages: List<ChatMessage>) {
        _messages.value = messages
        if (userId != 0L) {
            viewModelScope.launch {
                chatHistoryStorage.saveMessages(userId, messages)
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }

    /**
     * 记录用户对表情识别的授权选择
     */
    fun setEmotionCaptureConsent(granted: Boolean) {
        viewModelScope.launch {
            emotionCapturePrefs.setConsentGranted(granted)
        }
    }

    /**
     * 启动聊天页前台表情采集
     *
     * 仅在用户已授权、开关开启且已授予相机权限时由 ChatScreen 调用。
     * 采集结果经 EmotionSignalRepository 去重后上报后端。
     */
    fun startEmotionCapture(lifecycleOwner: LifecycleOwner) {
        if (_isEmotionCapturing.value) {
            return
        }
        val started = emotionCaptureController.start(lifecycleOwner) { label, confidence ->
            viewModelScope.launch {
                emotionSignalRepository.report(label, confidence)
            }
        }
        _isEmotionCapturing.value = started
    }

    /**
     * 停止表情采集（聊天页不可见时调用）
     */
    fun stopEmotionCapture() {
        if (!_isEmotionCapturing.value) {
            return
        }
        emotionCaptureController.stop()
        _isEmotionCapturing.value = false
    }

    private suspend fun uploadImagesForChat(imageUris: List<String>): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            if (imageUris.isEmpty()) {
                return@withContext ApiResult.Success(emptyList())
            }

            val uploadedUrls = mutableListOf<String>()

            for (uriString in imageUris.take(MAX_CHAT_IMAGES)) {
                val file = uriToTempFile(
                    uriString = uriString,
                    prefix = "chat_img_",
                    defaultExtension = "jpg",
                ) ?: return@withContext ApiResult.Error(-1, "无法读取图片文件")

                try {
                    val upload = drawingRepository.uploadSketch(file)
                    val rawUrl = upload.getOrElse { error ->
                        return@withContext ApiResult.Error(-1, error.message ?: "Unknown error")
                    }
                    uploadedUrls.add(resolveRemoteUrl(rawUrl))
                } finally {
                    runCatching { file.delete() }
                }
            }

            ApiResult.Success(uploadedUrls)
        }

    private suspend fun transcribeAudioFromUri(audioUri: String): Result<String> =
        withContext(Dispatchers.IO) {
            val audioFile = uriToTempFile(
                uriString = audioUri,
                prefix = "chat_voice_",
                defaultExtension = "m4a",
            ) ?: return@withContext Result.failure(Exception("无法读取语音文件"))

            try {
                var finalResult: Result<String>? = null
                voiceRepository.transcribeAudio(audioFile).collect { result ->
                    when (result) {
                        is ApiResult.Success -> finalResult = Result.success(result.data.text)
                        is ApiResult.Error -> {
                            finalResult = Result.failure(Exception(result.message))
                        }
                        is ApiResult.Loading -> Unit
                    }
                }
                finalResult ?: Result.failure(Exception("语音转写失败"))
            } finally {
                runCatching { audioFile.delete() }
            }
        }

    private fun JsonObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return entrySet().associate { entry ->
            val value = entry.value
            val normalized = when {
                value == null || value.isJsonNull -> ""
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                else -> value.toString().trim('"')
            }
            entry.key to normalized
        }
    }

    private fun uriToTempFile(
        uriString: String,
        prefix: String,
        defaultExtension: String,
    ): File? {
        return runCatching {
            val uri = Uri.parse(uriString)
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri)
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.takeIf { it.isNotBlank() }
                ?: defaultExtension

            val tempFile = File.createTempFile(prefix, ".$extension", appContext.cacheDir)
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            tempFile
        }.getOrNull()
    }

    private fun resolveRemoteUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }

        val normalizedPath = when {
            url.startsWith("/uploads/") -> "/media/${url.removePrefix("/uploads/")}"
            url.startsWith("uploads/") -> "/media/${url.removePrefix("uploads/")}"
            url.startsWith("/") -> url
            else -> "/$url"
        }
        val base = ApiEndpoints.apiBaseUrl.trimEnd('/')
        return "$base$normalizedPath"
    }

    companion object {
        private const val MAX_CHAT_IMAGES = 3
    }
}

data class PendingToolPlanUi(
    val planId: String,
    val tool: String,
    val title: String,
    val preview: String,
    val argumentsJson: String,
    val expiresAt: String?,
    val requiresConfirmation: Boolean,
)

data class EmotionServiceBundleUi(
    val bundleId: String,
    val title: String,
    val message: String,
    val triggerReason: String,
    val isCrisis: Boolean,
    val services: List<EmotionServiceActionUi>,
)

data class EmotionServiceActionUi(
    val id: String,
    val title: String,
    val description: String,
    val action: String,
    val payload: Map<String, String> = emptyMap(),
    val autoStart: Boolean = false,
)

data class ChatClientAction(
    val action: String,
    val payload: Map<String, String> = emptyMap(),
)
