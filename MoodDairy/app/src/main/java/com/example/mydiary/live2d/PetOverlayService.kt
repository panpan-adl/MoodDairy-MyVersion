package com.example.mydiary.live2d

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.models.ChatConfirmation
import com.example.mydiary.data.models.ChatRequest
import com.example.mydiary.data.models.ChatStreamEvent
import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.di.AuthInterceptor
import com.example.mydiary.ui.chat.ChatClientAction
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 小猫桌宠悬浮窗服务：
 * - 打开 App 时由 MainActivity 启动，悬浮在界面右下角，可拖动
 * - Capture companion input and hand it to the assistant conversation UI.
 */
@AndroidEntryPoint
class PetOverlayService : Service() {

    companion object {
        private const val TAG = "PetOverlayService"
        private const val CAT_SIZE_DP = 150
        private const val BUBBLE_WIDTH_DP = 190
        private const val DRAG_SLOP_DP = 8

        /** 当前运行中的桌宠实例（Live2D 页独占渲染协作用） */
        @Volatile
        private var instance: PetOverlayService? = null

        private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running
        private var host = java.lang.ref.WeakReference<android.app.Activity>(null)
        // A fresh app process starts with companionship enabled. Activity/service
        // recreation and returning from the background keep this session's choice.
        private val _enabled = kotlinx.coroutines.flow.MutableStateFlow(true)
        val enabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _enabled
        var requestMicrophone: (() -> Unit)? = null
        private var pendingVoiceStart = false
        @Volatile private var assistantPage = false
        @Volatile private var showcasePage = false
        private val composerBoundary = CompanionBoundary()
        fun setComposerBoundary(owner: Any, topInWindow: Float?) {
            composerBoundary.update(owner, topInWindow)
            instance?.schedulePositionCat()
        }
        fun setShowcasePage(value: Boolean) {
            showcasePage = value
            instance?.mainHandler?.post { instance?.applyPagePresence() }
        }
        fun setChatReplyBusy(busy: Boolean) {
            instance?.let { service ->
                service.isChatting = busy
                if (!busy) service.scheduleVoiceResume()
            }
        }
        fun refreshVoiceWake() {
            instance?.let { service ->
                service.mainHandler.post {
                    service.manualVoiceInput?.stop()
                    service.isListening = false
                    service.manualVoiceInput?.resetWakeFailure()
                    service.voiceInputFailed = false
                    if (!VoiceWakeSettings.enabled.value) {
                        service.cancelVoiceSession()
                    }
                    service.scheduleVoiceResume()
                }
            }
        }
        private var voiceSessionActivated = false

        fun setAssistantPage(assistant: Boolean) {
            assistantPage = assistant
            instance?.let { service -> service.mainHandler.post {
                // Preserve exit cancellation even if navigation returns before this post runs.
                if (!assistant) service.cancelVoiceSession()
                service.applyPagePresence()
            } }
        }

        fun clearVoiceActivation() { voiceSessionActivated = false }


        fun attach(activity: android.app.Activity) {
            CompanionAppearance.initialize(activity)
            host = java.lang.ref.WeakReference(activity)
            activity.window.decorView.post {
                if (host.get() === activity && _enabled.value && !activity.isFinishing &&
                    activity.window.decorView.isAttachedToWindow &&
                    (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                        ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true) {
                    activity.startService(startIntent(activity))
                }
            }
        }

        fun detach(activity: android.app.Activity) {
            if (host.get() === activity) {
                host.clear()
                instance?.cancelVoiceSession()
                instance?.pauseCharacter()
                activity.stopService(startIntent(activity))
            }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            _enabled.value = enabled
            if (!enabled) {
                instance?.cancelVoiceSession()
                voiceSessionActivated = false
                pendingVoiceStart = false
            }
            if (!enabled) {
                instance?.pauseCharacter()
                context.stopService(startIntent(context))
            }
            else host.get()?.let { attach(it) }
        }

        fun onMicrophoneResult(granted: Boolean) {
            if (!_enabled.value || !assistantPage || showcasePage) return
            pendingVoiceStart = granted && instance == null
            instance?.let {
                if (granted) it.onTapCat()
                else {
                    it.showBubble("可以先打字跟然然聊聊～")
                    it.toggleTextInput(true)
                }
            }
        }


        fun startIntent(context: Context): Intent = Intent(context, PetOverlayService::class.java)

        /**
         * 桌宠收到的「操作 App」指令（后端 client_action 事件，或本地关键词兜底）。
         * MainActivity 订阅它做真实导航，让「帮我写日记」这类话能直接跳到对应页面。
         */
        private val _clientActions = MutableSharedFlow<ChatClientAction>(extraBufferCapacity = 8)
        val clientActions: SharedFlow<ChatClientAction> = _clientActions

        /** 指令命中后然然说的确认语（避免把后端的「**完成**」直接念出来） */
        private fun confirmationFor(action: String): String = when (action) {
            "open_diary_editor" -> "好的，帮你打开日记本啦～"
            "create_diary" -> "好的，这就帮你记下来～"
            "open_todo_create" -> "好的，待办给你打开啦～"
            "open_white_noise" -> "好呀，白噪音给你安排上～"
            "play_white_noise" -> "好呀，这就给你播放～"
            "stop_white_noise" -> "好，停了～"
            "open_test_hub", "open_test" -> "好呀，心理测评在这儿～"
            "open_drawing" -> "好呀，一起来画画～"
            "open_calendar" -> "好，回日历啦～"
            "open_chat" -> "来聊天吧～"
            "open_pomodoro" -> "好呀，开始专注～"
            "open_highlights" -> "看看你的高光时刻～"
            "open_little_joys" -> "看看你的小确幸～"
            "open_growth_portrait" -> "看看你的成长画像～"
            "open_achievement" -> "看看你的成就～"
            "open_shop" -> "商城在这里～"
            "open_live2d" -> "Live2D 来啦～"
            "open_profile" -> "你的资料页～"
            "update_profile" -> "好的，帮你改～"
            "open_diary_search" -> "好，去搜日记～"
            "open_explore" -> "带你去探索～"
            else -> "好的，帮你打开啦～"
        }

        /**
         * 最近一次搜索结果（链接跳转指令用）。存 companion 让 App 任何地方都能访问，
         * 跳转时 Intent.ACTION_VIEW 需要 NEW_TASK flag（Service 里启 Activity 必须加）。
         */
        @Volatile var recentSearchResults: List<com.example.mydiary.data.models.SocialSearchItem>? = null
        @Volatile var recentSearchKeyword: String? = null
        @Volatile var recentSearchLocked: Boolean = false

        /**
         * 然然「创建日记」指令的预填数据（标题/内容），DiaryEditorScreen 启动时读取并清空。
         */
        @Volatile var pendingDiaryTitle: String? = null
        @Volatile var pendingDiaryContent: String? = null

        /**
         * 然然「修改资料」指令的目标字段和值，EditProfileScreen 启动时读取并清空。
         */
        @Volatile var pendingProfileField: String? = null
        @Volatile var pendingProfileValue: String? = null
    }

    @Inject lateinit var sessionManager: SessionManager

    @Inject lateinit var authInterceptor: AuthInterceptor

    @Inject
    @Named("shortTimeout")
    lateinit var okHttpClient: OkHttpClient

    @Inject lateinit var gson: Gson

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appearanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false

    private var characterView: Live2DView? = null
    private var closePopup: PopupWindow? = null
    private var rootView: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var petPosition: android.graphics.Point? = null

    /** 打字聊天（语音服务连不上时的兜底通道） */
    private var inputRow: LinearLayout? = null
    private var inputEdit: EditText? = null
    private var inputVisible = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val localSpeechCallbacks = mutableMapOf<String, () -> Unit>()

    /** 当前正在播放 TTS 的 MediaPlayer，打断时需要停止 */
    private var currentTtsPlayer: android.media.MediaPlayer? = null
    @Volatile private var dialogueGeneration = 0L
    @Volatile private var activeChatCall: okhttp3.Call? = null
    private val sentencePlayer by lazy {
        CompanionSpeechPlayer(onBusy = { busy ->
            ttsSpeaking = busy
            com.example.mydiary.whitenoise.WhiteNoiseManager.getInstance().setSpeechActive(busy)
            if (!busy && !destroyed && !isChatting && !isListening) {
                scheduleRetreat()
                scheduleVoiceResume()
            }
        }, fallback = { text, done ->
            if (ttsReady && tts?.voice?.isNetworkConnectionRequired == false) {
                val id = "fallback-${UUID.randomUUID()}"
                localSpeechCallbacks[id] = done
                val status = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                if (status == TextToSpeech.ERROR) localSpeechCallbacks.remove(id)?.invoke()
            } else done()
        })
    }

    private var isListening = false
    private var isChatting = false

    // ===== 常听模式：一直听，只有喊「然然」才应答 =====
    private var voiceActivated = voiceSessionActivated
    @Volatile private var wakeListening = false

    /** 然然正在念话：必须让麦克风闭嘴，否则会把自己的声音当成唤醒词（自触发） */
    @Volatile private var ttsSpeaking = false
    @Volatile private var ttsGeneration = 0L

    /** 唤醒后等待指令的截止时间（SystemClock.uptimeMillis），0 = 不在指令窗口 */
    private var awaitCommandUntil = 0L

    // System speech recognition; availability and network/model support depend on the device.
    private var manualVoiceInput: ForegroundVoiceInput? = null
    private var continuousConversation = false
    private var voiceInputFailed = false
    private val resumeVoice = Runnable { maybeStartWakeListening() }
    private var manualListenTimeout: Runnable? = null

    private fun clearManualListenTimeout() {
        manualListenTimeout?.let(mainHandler::removeCallbacks)
        manualListenTimeout = null
    }


    private val greetings = listOf(
        "我是然然，喊一声「然然」，我就在这儿听着呢",
        "今天过得怎么样呀？",
        "把坏心情交给我，我帮你吃掉！",
        "喊「然然」，我就应你，一直听着哦",
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (host.get() == null) {
            stopSelf()
            return
        }
        Log.d(TAG, "桌宠服务启动")
        instance = this
        initTts()
        try {
            if (!showcasePage) showCat()
        } catch (error: android.view.WindowManager.BadTokenException) {
            Log.w(TAG, "Activity window is no longer available", error)
            stopSelf()
            return
        }
        applyPagePresence()
        _running.value = true
        // Prepare local wake detection as soon as the assistant page is eligible.
        initSherpaEngine()
        if (pendingVoiceStart) {
            pendingVoiceStart = false
            mainHandler.post { onTapCat() }
        }
    }

    /** Initialize the system recognizer used by manual input and wake listening. */
    private fun initSherpaEngine() {
        VoiceWakeSettings.initialize(this)
        val callback = object : SherpaAsrEngine.Callback {
            override fun onReady() = Unit
            override fun onSegment(text: String) {
                clearManualListenTimeout()
                isListening = false
                val clean = VoiceSessionText.stripWake(text)
                if (VoiceSessionText.endsConversation(text)) {
                    continuousConversation = false
                    showBubble("好，需要时再喊然然～")
                    scheduleVoiceResume()
                } else if (clean.isBlank()) {
                    scheduleVoiceResume()
                } else {
                    hideBubble()
                    sendToChat(clean)
                }
            }
            override fun onProcessing() {
                isListening = false
                VoiceWakeSettings.updateStatus("正在识别…")
                showBubble("正在识别…")
            }
            override fun onError(message: String) {
                isListening = false
                continuousConversation = false
                voiceInputFailed = message.startsWith("麦克风") || message.startsWith("本地唤醒")
                VoiceWakeSettings.updateStatus(message)
                showBubble(message)
                if (!voiceInputFailed) scheduleVoiceResume()
            }
        }
        manualVoiceInput = ForegroundVoiceInput(this, okHttpClient, callback,
            onWake = {
                continuousConversation = true
                isListening = true
                revealForInteraction()
                showBubble("我在听…")
                VoiceWakeSettings.updateStatus("我在听…")
                runCatching {
                    android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 30).also { tone ->
                        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
                        mainHandler.postDelayed({ tone.release() }, 150)
                    }
                }
            },
            onIdle = {
                isListening = false
                continuousConversation = false
                hideBubble()
                scheduleVoiceResume()
            },
            allowed = { voiceHostAvailable() }
        )
        maybeStartWakeListening()
    }

    private fun voiceHostAvailable(): Boolean {
        val activity = host.get() ?: return false
        return AssistantVoicePolicy.available(
            assistant = assistantPage, showcase = showcasePage, enabled = !destroyed && _enabled.value,
            resumed = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true,
            unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked,
            interactive = getSystemService(android.os.PowerManager::class.java).isInteractive,
            microphone = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun scheduleVoiceResume() {
        mainHandler.removeCallbacks(resumeVoice)
        if (!destroyed) mainHandler.postDelayed(resumeVoice, 500)
    }

    private fun cancelVoiceSession() {
        dialogueGeneration++
        ttsGeneration++
        activeChatCall?.cancel()
        activeChatCall = null
        clearManualListenTimeout()
        manualVoiceInput?.stop()
        wakeListening = false
        mainHandler.removeCallbacks(resumeVoice)
        localSpeechCallbacks.clear()
        sentencePlayer.clear(notify = false)
        runCatching { currentTtsPlayer?.stop() }
        runCatching { currentTtsPlayer?.release() }
        currentTtsPlayer = null
        runCatching { tts?.stop() }
        isListening = false
        isChatting = false
        ttsSpeaking = false
        continuousConversation = false
        VoiceWakeSettings.updateStatus(if (!assistantPage || showcasePage)
            "已暂停，请返回助手界面" else VoiceWakeSettings.WAITING)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.CHINA
                // 实测本机普通话本地音色：ssa(≈257Hz)/ccc(≈222Hz) 为年轻女声，cce/ccd 为男声。
                // 优先本地（不依赖联网）普通话年轻女声
                val zhVoices = runCatching { tts?.voices }.getOrNull().orEmpty()
                zhVoices.filter { it.locale.language == "zh" }.forEach {
                    Log.d(TAG, "可用中文音色: ${it.name} quality=${it.quality} network=${it.isNetworkConnectionRequired}")
                }
                val localMandarin = zhVoices.filter {
                    !it.isNetworkConnectionRequired &&
                        (it.name.startsWith("cmn-cn", ignoreCase = true) ||
                            it.name.startsWith("zh-cn", ignoreCase = true))
                }
                fun pickByCode(code: String) =
                    localMandarin.firstOrNull { it.name.contains(code, ignoreCase = true) }
                val preferred = pickByCode("ssa") ?: pickByCode("ccc") ?: localMandarin.maxByOrNull { it.quality }
                if (preferred != null) {
                    tts?.voice = preferred
                    Log.d(TAG, "然然选用音色: ${preferred.name}")
                } else {
                    Log.w(TAG, "本机无本地普通话音色，使用引擎默认音色（可能需要联网下载语音数据）")
                }
                // 不再做变调（pitch 变化会在 embedded 声线上产生金属电音感），只轻微放慢语速
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.97f)
                // 念话期间暂停常听，念完再接着听（避免然然听自己说话而自触发）
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ttsSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith("fallback-") == true) {
                            mainHandler.post { localSpeechCallbacks.remove(utteranceId)?.invoke() }
                            return
                        }
                        ttsSpeaking = false
                        mainHandler.post { scheduleRetreat() }
                        // 留 0.5 秒等喇叭里的尾音散干净，免得然然听见自己最后半句
                        mainHandler.postDelayed({ maybeStartWakeListening() }, 500)
                    }

                    @Deprecated("系统回调必实现")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith("fallback-") == true) {
                            mainHandler.post { localSpeechCallbacks.remove(utteranceId)?.invoke() }
                            return
                        }
                        ttsSpeaking = false
                        mainHandler.post { scheduleRetreat() }
                        mainHandler.postDelayed({ maybeStartWakeListening() }, 500)
                    }
                })
                Log.d(TAG, "TTS 就绪")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCat() {
        val density = resources.displayMetrics.density
        val catSize = (CAT_SIZE_DP * density).toInt()

        // 气泡（小猫上方，默认隐藏；长按复制文本）
        val bubble = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Color.rgb(70, 60, 50))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 255, 252, 245))
                cornerRadius = 14 * density
                setStroke((2 * density).toInt(), Color.argb(255, 155, 237, 207))
            }
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnLongClickListener {
                val txt = text?.toString().orEmpty()
                if (txt.isNotBlank()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("然然回复", txt))
                    android.widget.Toast.makeText(this@PetOverlayService, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
            // 点一下气泡也能手动隐藏（长回复看不完时方便收起）
            setOnClickListener { hideBubble() }
        }

        // 打字聊天兜底（语音服务连不上时用）：输入行 + 入口按钮
        val edit = EditText(this).apply {
            hint = "跟然然说点什么…"
            textSize = 13f
            setTextColor(Color.rgb(70, 60, 50))
            setHintTextColor(Color.argb(150, 130, 130, 130))
            setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 255, 255, 255))
                cornerRadius = 10 * density
                setStroke((2 * density).toInt(), Color.argb(255, 155, 237, 207))
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendInputText(); true } else false
            }
        }
        inputEdit = edit

        val sendBtn = TextView(this).apply {
            text = "发"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isSoundEffectsEnabled = true
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 120, 205, 170))
                cornerRadius = 10 * density
            }
            setOnClickListener {
                Log.i(TAG, "发按钮点击触发")
                sendInputText()
            }
        }

        val inputRowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                leftMargin = (6 * density).toInt()
            })
        }
        inputRow = inputRowView

        // Native cat keeps the companion available even without the Tororo asset.
        val cat = Live2DView(this).apply {
            contentDescription = "然然"
            layoutParams = FrameLayout.LayoutParams(catSize, (190 * density).toInt())
        }
        characterView = cat

        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(127, 140, 141))
            contentDescription = "关闭悬浮陪伴"
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            setOnClickListener { setEnabled(this@PetOverlayService, false) }
        }
        // A tiny separate app window can sit beside the ear, above Live2D's
        // on-top SurfaceView, without a large circle or an empty header row.
        val closeSize = (24 * density).toInt()
        val popup = PopupWindow(closeButton, closeSize, closeSize, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 0f
        }
        closePopup = popup
        fun updateClosePopup() {
            if (destroyed || !_enabled.value || !cat.isShown || !cat.isAttachedToWindow) {
                popup.dismiss()
                return
            }
            val xOffset = cat.width - closeSize - (14 * density).toInt()
            val yOffset = -cat.height + (42 * density).toInt()
            if (popup.isShowing) popup.update(cat, xOffset, yOffset, closeSize, closeSize)
            else popup.showAsDropDown(cat, xOffset, yOffset, Gravity.START)
        }
        val hideCloseButton = Runnable { popup.dismiss() }
        closeButton.setOnHoverListener { _, event ->
            mainHandler.removeCallbacks(hideCloseButton)
            if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                mainHandler.postDelayed(hideCloseButton, 150)
            }
            false
        }
        val characterContainer = object : FrameLayout(this) {
            override fun dispatchHoverEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        mainHandler.removeCallbacks(hideCloseButton)
                        updateClosePopup()
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        mainHandler.removeCallbacks(hideCloseButton)
                        mainHandler.postDelayed(hideCloseButton, 150)
                    }
                }
                super.dispatchHoverEvent(event)
                // Remain a hover target so Android sends EXIT even over the
                // non-clickable GL surface. Touch/drag dispatch is unchanged.
                return true
            }
        }.apply {
            addView(cat)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble, LinearLayout.LayoutParams(
                (BUBBLE_WIDTH_DP * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (4 * density).toInt() })
            addView(inputRowView, LinearLayout.LayoutParams(
                (BUBBLE_WIDTH_DP * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (4 * density).toInt() })
            addView(characterContainer, LinearLayout.LayoutParams(catSize, (190 * density).toInt()))
        }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (popup.isShowing) updateClosePopup()
            schedulePositionCat()
        }
        rootView = root
        bubbleText = bubble

        // 拖动 + 单击说话（须同时挂在根布局和小猫视图上：
        // GLSurfaceView 会自行消费触摸，只挂根布局会收不到小猫区域的触摸）
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var moved = false
        var downAt = 0L
        val slop = (DRAG_SLOP_DP * density).toInt()

        val petTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.i(TAG, "cat ACTION_DOWN raw=(${event.rawX},${event.rawY})")
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = petPosition?.x ?: 0
                    downAt = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        petPosition?.let {
                            it.x = startX + dx
                            positionCat()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Log.i(TAG, "cat ACTION_UP moved=$moved dur=${System.currentTimeMillis() - downAt}")
                    if (!moved) {
                        if (System.currentTimeMillis() - downAt >= 600) {
                            Log.i(TAG, "→ 长按 → toggleTextInput")
                            toggleTextInput(!inputVisible)
                        } else {
                            Log.i(TAG, "→ 单击 → onTapCat")
                            onTapCat()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        // Only the cat handles dragging; other app controls remain touchable.
        cat.setOnTouchListener(petTouchListener)
        Log.i(TAG, "petTouchListener attached: rootView=${rootView != null} cat=${cat != null}")

        val dm = resources.displayMetrics
        petPosition = android.graphics.Point(
            (dm.widthPixels - (BUBBLE_WIDTH_DP * density).toInt() - (12 * density).toInt()).coerceAtLeast(0),
            0,
        )
        val parent = host.get()?.window?.decorView as? android.view.ViewGroup
            ?: throw android.view.WindowManager.BadTokenException("Activity decor is unavailable")
        root.visibility = View.INVISIBLE
        parent.addView(root, android.widget.FrameLayout.LayoutParams(
            (BUBBLE_WIDTH_DP * density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.START })
        schedulePositionCat()
        CompanionAppearance.initialize(this)
        appearanceScope.launch {
            CompanionAppearance.current.collectLatest { model ->
                delay(400)
                if (!destroyed && characterView === cat && cat.isAttachedToWindow) {
                    cat.loadModel(model.path)
                }
            }
        }

    }

    private fun pauseCharacter() {
        closePopup?.dismiss()
        rootView?.visibility = View.GONE
        releaseCharacter()
    }

    private fun releaseCharacter() {
        val view = characterView ?: return
        characterView = null
        // Drain the old GL thread before the full digital-human page starts rendering.
        view.release()
        view.onPause()
    }

    private val positionCatFrame = Runnable { positionCat() }

    private fun schedulePositionCat() {
        val root = rootView ?: return
        root.removeCallbacks(positionCatFrame)
        root.postOnAnimation(positionCatFrame)
    }

    private fun positionCat() {
        val root = rootView ?: return
        val position = petPosition ?: return
        if (!assistantPage || showcasePage) { root.visibility = View.GONE; return }
        val boundary = composerBoundary.topInWindow
        val parent = root.parent as? android.view.ViewGroup ?: return
        if (boundary == null || root.height == 0 || parent.width == 0) {
            root.visibility = View.INVISIBLE
            return
        }
        val origin = IntArray(2)
        parent.getLocationInWindow(origin)
        position.y = CompanionAnchorGeometry.top(boundary, origin[1], parent.paddingTop, root.height)
        position.x = CompanionAnchorGeometry.horizontal(position.x, parent.width, parent.paddingLeft, parent.paddingRight, root.width)
        val params = root.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        if (params.leftMargin != position.x || params.topMargin != position.y) {
            params.leftMargin = position.x
            params.topMargin = position.y
            root.layoutParams = params
        }
        root.visibility = View.VISIBLE
    }

    /** 点小猫：开始听你说话 */
    private fun onTapCat() {
        if (!assistantPage || showcasePage) return
        localSpeechCallbacks.clear()
        dialogueGeneration++
        activeChatCall?.cancel()
        isChatting = false
        if (inputVisible) toggleTextInput(false)
        if (isListening) {
            stopListening()
            return
        }
        // Stop the character's own voice before opening the microphone.
        ttsGeneration++
        sentencePlayer.clear()
        ttsSpeaking = false
        runCatching { currentTtsPlayer?.stop() }
        runCatching { currentTtsPlayer?.release() }
        currentTtsPlayer = null
        runCatching { tts?.stop() }
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            requestMicrophone?.invoke()
            return
        }
        // With wake disabled a tap records one turn, without automatic microphone resume.
        continuousConversation = VoiceWakeSettings.enabled.value
        voiceInputFailed = false
        voiceActivated = false
        voiceSessionActivated = false
        startListening()
    }

    private fun startListening() {
        if (isListening) return
        stopWakeListening() // 手动听时先让出麦克风
        if (manualVoiceInput == null) {
            showBubble("语音输入正在准备，请稍后再试")
            return
        }
        startSherpaListening()
    }

    /** Sherpa 手动聆听：点一下猫，下一段语音直接当聊天内容 */
    private fun startSherpaListening() {
        if (isListening) return
        val engine = manualVoiceInput ?: return
        stopWakeListening()
        isListening = true
        showBubble("我在听，说完可以再点我一下。")
        if (!engine.start()) isListening = false
        // 15 秒没识别到内容自动结束
        clearManualListenTimeout()
        // Silence (20s) and utterance limits are enforced by the microphone worker.
    }

    // ===== 常听模式：一直听，只有喊「然然」才应答 =====

    /** Automatic listening is permitted only on the visible assistant page. */
    private fun maybeStartWakeListening() {
        when (AssistantVoicePolicy.resume(
            available = voiceHostAvailable() && !voiceInputFailed,
            busy = isListening || isChatting || ttsSpeaking || inputVisible,
            wakeEnabled = VoiceWakeSettings.enabled.value,
            continuous = continuousConversation,
        )) {
            AssistantVoicePolicy.Resume.CONTINUE_CONVERSATION -> {
                isListening = true
                showBubble("我在听…")
                VoiceWakeSettings.updateStatus("我在听…")
                if (manualVoiceInput?.start() != true) isListening = false
            }
            AssistantVoicePolicy.Resume.WAIT_FOR_WAKE -> {
                VoiceWakeSettings.updateStatus(VoiceWakeSettings.WAITING)
                manualVoiceInput?.standby()
            }
            AssistantVoicePolicy.Resume.NONE -> Unit
        }
    }

    private fun stopWakeListening() {
        mainHandler.removeCallbacks(resumeVoice)
        wakeListening = false
        manualVoiceInput?.stop()
    }

    private fun stopListening() {
        clearManualListenTimeout()
        // Final recognition arrives asynchronously; stay in manual mode until it does.
        manualVoiceInput?.finish()
    }

    /** Focus the native input inside the activity; never replace the app's window flags. */
    private fun toggleTextInput(show: Boolean) {
        if (show) {
            clearManualListenTimeout()
            manualVoiceInput?.stop()
            isListening = false
        }
        val root = rootView ?: return
        if (inputVisible == show) return
        if (show && root.visibility != View.VISIBLE) return
        inputVisible = show
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            hideBubble()
            inputRow?.visibility = View.VISIBLE
            schedulePositionCat()
            mainHandler.postDelayed({
                val edit = inputEdit ?: return@postDelayed
                edit.requestFocus()
                runCatching { imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
            }, 150)
        } else {
            runCatching { inputEdit?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) } }
            inputEdit?.clearFocus()
            inputRow?.visibility = View.GONE
            schedulePositionCat()
            scheduleRetreat()
            maybeStartWakeListening() // 收起打字框后恢复常听
        }
    }

    /** 发送打字内容（走和小嘉然一样的聊天通道） */
    private fun sendInputText() {
        val raw = inputEdit?.text?.toString()
        val text = raw?.trim().orEmpty()
        Log.i(TAG, "Companion text input submitted")
        if (text.isEmpty()) {
            Log.w(TAG, "sendInputText: text empty, abort (hint=${inputEdit?.hint})")
            return
        }
        inputEdit?.setText("")
        toggleTextInput(false)
        sendToChat(text)
    }

    /** The companion only captures input; the chat screen owns conversation and replies. */
    private fun sendToChat(text: String) {
        val clean = (PetIntentRouter.stripWakeWord(text)?.takeIf { it.isNotBlank() } ?: text).trim()
        val userId = sessionManager.getUserId()
        if (userId == null || userId <= 0L) {
            showBubble("请先登录，再使用语音输入")
            scheduleVoiceResume()
            return
        }
        if (!assistantPage || showcasePage || clean.isBlank()) return
        hideBubble()
        if (!CompanionChatInput.submit(userId, clean)) showBubble("输入队列已满，请稍后再试")
        scheduleVoiceResume()
    }

    private var temporarilyVisible = false
    private val retreatRunnable = object : Runnable {
        override fun run() {
            if (assistantPage || !temporarilyVisible) return
            if (isListening || isChatting || ttsSpeaking || inputVisible ||
                android.os.SystemClock.uptimeMillis() < awaitCommandUntil) {
                mainHandler.postDelayed(this, 1000)
            } else {
                temporarilyVisible = false
                hideBubble()
                rootView?.visibility = View.GONE
            }
        }
    }

    private fun scheduleRetreat() {
        if (destroyed) return
        mainHandler.removeCallbacks(retreatRunnable)
        if (!assistantPage && temporarilyVisible) mainHandler.postDelayed(retreatRunnable, 10_000)
    }

    private fun revealForInteraction() {
        if (showcasePage) return
        temporarilyVisible = true
        schedulePositionCat()
        scheduleRetreat()
    }

    private fun applyPagePresence() {
        if (!showcasePage && rootView == null && !destroyed) showCat()
        mainHandler.removeCallbacks(retreatRunnable)
        temporarilyVisible = false
        if (!assistantPage || showcasePage) {
            cancelVoiceSession()
            if (inputVisible) toggleTextInput(false)
            hideBubble()
        } else {
            voiceInputFailed = false
            manualVoiceInput?.resetWakeFailure()
            scheduleVoiceResume()
        }
        if (!assistantPage || showcasePage) rootView?.visibility = View.GONE
        else schedulePositionCat()
    }

    /** 气泡自动隐藏延迟（毫秒）。30 秒足够用户看清长搜索结果，点击气泡可手动提前收起 */
    private val BUBBLE_AUTO_HIDE_MS = 30_000L

    private fun showBubble(text: String) {
        if (destroyed || (!assistantPage && !temporarilyVisible)) return
        scheduleRetreat()
        bubbleText?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
        // 每次显示都重置计时器，避免连续说话时新气泡被旧 timer 抢先隐藏
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, BUBBLE_AUTO_HIDE_MS)
    }

    private fun hideBubble() {
        bubbleText?.visibility = View.GONE
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    private fun speak(text: String) {
        if (destroyed || host.get() == null) return
        showBubble(text)
        stopWakeListening() // 念话期间闭嘴，否则然然会听见自己的声音而自触发
        ttsSpeaking = true

        // 从后端 /tts 取 edge-tts 真人声播放，替代系统 TTS
        playServerTts(text)

        // showBubble 已经管了 timer（30 秒），这里无需重复 post
    }

    /** 从后端 /tts 拉取 edge-tts 真人语音并流式播放 */
    private fun playServerTts(text: String) {
        localSpeechCallbacks.clear()
        runCatching { tts?.stop() }
        sentencePlayer.clear(notify = false)
        val sentences = SpeechSentences()
        (sentences.append(text) + sentences.finish()).forEach(sentencePlayer::enqueue)
    }

    private val autoHideRunnable = Runnable { if (!isListening && !isChatting) hideBubble() }

    override fun onDestroy() {
        localSpeechCallbacks.clear()
        destroyed = true
        dialogueGeneration++
        activeChatCall?.cancel()
        sentencePlayer.clear()
        appearanceScope.cancel()
        clearManualListenTimeout()
        super.onDestroy()
        instance = null
        _running.value = false
        stopWakeListening()
        mainHandler.removeCallbacksAndMessages(null)
        closePopup?.dismiss()
        closePopup = null
        runCatching { currentTtsPlayer?.release() }
        currentTtsPlayer = null
        releaseCharacter()
        runCatching { rootView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } }
        rootView = null
        bubbleText = null
        manualVoiceInput?.release()
        manualVoiceInput = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        Log.d(TAG, "桌宠服务退出")
    }
}

/** JsonObject → Map<String,String>（与 ChatViewModel 里的同名实现保持一致） */
private fun com.google.gson.JsonObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return entrySet().associate { (key, value) ->
        key to when {
            value == null || value.isJsonNull -> ""
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
            else -> value.toString().trim('"')
        }
    }
}

/**
 * 本地关键词兜底：不联网、零延迟地认出「打开某页面」这类指令，
 * 说法与后端 client_intents.py 对齐，输出的 action 名与 NavGraph 的 handleClientAction 一致。
 *
 * 只认明确的祈使句（打开/帮我/想…）或纯指令词，避免「我今天待办好多啊」这种聊天被误判成跳页。
 */
private object PetIntentRouter {

    /** 唤醒词（含常见同音误识别，离线小模型容易听成这些） */
    private val wakeWords = listOf("小然然", "小嘉然", "然然", "嘉然", "冉冉", "燃燃")

    /** 只喊了「打开」没说要开什么时，然然这么问 */
    const val OPEN_PROMPT = "要打开哪个呀？日记、待办、白噪音还是画画？说一个就行～"

    /** 光说一个词也算指令 */
    private val bareCommands = setOf("待办", "日记", "写日记", "白噪音", "白噪声", "测评", "测试中心", "画画")

    /** 祈使句开头 */
    private val imperatives = listOf(
        "打开", "请打开", "帮我打开", "帮我", "给我", "请帮", "我要", "我想", "想听", "想",
        "去", "跳转", "进入", "新建", "记个", "记一下", "记一条", "加个", "添加", "加一个",
        "放点", "来点", "放", "开始", "启动", "写", "播放", "播", "听"
    )

    /** 「打开」类动词 */
    private val openVerbs = listOf("打开", "帮我打开", "启动", "进入", "跳到", "跳转")

    private fun compact(raw: String) = raw.filterNot { it.isWhitespace() }

    fun containsWakeWord(raw: String): Boolean {
        val text = compact(raw)
        return wakeWords.any { text.contains(it) }
    }

    /** 命中唤醒词 → 返回摘掉唤醒词后的剩余内容（可能为空串）；没命中 → null */
    fun stripWakeWord(raw: String): String? {
        val text = compact(raw)
        val hit = wakeWords.firstOrNull { text.contains(it) } ?: return null
        return text.replace(hit, "").trim('，', ',', '。', '.', '、', '！', '!', '?', '？', '：', ':')
    }

    /** 只说了「打开」却没说要打开什么 */
    fun looksLikeOpenOnly(raw: String): Boolean {
        val text = compact(raw)
        if (text.length > 8) return false
        if (openVerbs.none { text.contains(it) }) return false
        return match(text) == null
    }

    fun match(raw: String): ChatClientAction? {
        val text = compact(raw)
        if (text.isEmpty() || text.length > 48) return null
        // 「别打开日记」「我不想写日记」这类否定/疑问不算指令
        if (listOf("别", "不要", "不想", "不", "没", "吗", "?").any { text.contains(it) }) return null
        // 语音识别常带杂字：短句里出现祈使词就算（长句仍要求以祈使词开头）
        val imperative = imperatives.any { text.startsWith(it) } ||
            text in bareCommands ||
            (text.length <= 12 && imperatives.any { text.contains(it) })
        if (!imperative) return null

        val today = java.time.LocalDate.now().toString()
        fun has(vararg keys: String) = keys.any { text.contains(it) }

        // 先检查带参数的复杂指令（成功直接返回，不进入 when）
        extractDiaryCreation(text)?.let { return it }
        extractProfileUpdate(text)?.let { return it }

        return when {
            // 停止白噪音（最高优先级，「停下/别放了」）
            has("停止白噪音", "关闭白噪音", "别放了", "停止播放", "暂停播放", "停止声音") ->
                ChatClientAction("stop_white_noise")

            // 回日历 / 回首页
            has("回日历", "回到日历", "回首页", "回到首页", "回主页") ->
                ChatClientAction("open_calendar")
            // 跳转聊天页
            has("和小嘉然聊", "打开聊天", "去聊天", "进入聊天", "和小嘉然说话") ->
                ChatClientAction("open_chat")
            // 番茄钟
            has("番茄钟", "开始专注", "专注模式", "番茄") ->
                ChatClientAction("open_pomodoro")
            // 高光时刻
            has("高光时刻", "高光", "看看高光") -> ChatClientAction("open_highlights")
            // 小确幸
            has("小确幸", "小幸运", "看看小确幸") -> ChatClientAction("open_little_joys")
            // 成长画像
            has("成长画像", "成长报告", "我的成长", "看看成长") ->
                ChatClientAction("open_growth_portrait")
            // 成就
            has("成就", "看看成就", "我的成就") -> ChatClientAction("open_achievement")
            // 商城
            has("商城", "商店", "去商城") -> ChatClientAction("open_shop")
            // Live2D 互动页
            has("live2d", "二次元", "虚拟形象") -> ChatClientAction("open_live2d")
            // 个人主页
            has("个人主页", "我的资料", "个人信息", "打开资料") ->
                ChatClientAction("open_profile")
            // 日记搜索
            has("搜索日记", "找日记", "查日记") -> ChatClientAction("open_diary_search")
            // 探索
            has("探索", "发现") -> ChatClientAction("open_explore")

            has("待办", "代办", "待半") -> ChatClientAction("open_todo_create", mapOf("todo_date" to today))
            has("日记", "日志", "日纪", "日机", "写日") ->
                ChatClientAction("open_diary_editor", mapOf("diary_date" to today))
            has("白噪音", "白噪声", "白燥音") -> {
                // 检查是否指定了具体声音名（「播放水滴声」→ 直接播放）
                val soundName = extractSoundName(text)
                if (soundName != null) {
                    ChatClientAction("play_white_noise", mapOf("sound_name" to soundName))
                } else {
                    ChatClientAction("open_white_noise")
                }
            }
            // 雨声/具体自然声音：直接播放对应音频
            has("雨声", "雨音") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "雨声"))
            has("水滴") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "水滴"))
            has("鸟鸣", "鸟叫") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "鸟鸣"))
            has("篝火") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "篝火"))
            has("海浪") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "海浪"))
            has("森林") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "森林"))
            has("风声") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "风声"))
            has("雷声") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "雷声"))
            has("猫咪", "猫叫", "猫呼噜") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "猫咪呼噜"))
            has("钢琴") -> ChatClientAction("play_white_noise", mapOf("sound_name" to "钢琴"))
            has("测评", "心理测试", "测试中心", "测验", "测试") -> ChatClientAction("open_test_hub")
            has("画画", "画图", "涂鸦", "简笔画", "绘画", "画一张") -> ChatClientAction("open_drawing")
            else -> null
        }
    }

    /**
     * 识别「创建日记叫小团」「写日记标题是小团内容是今天很开心」这类带标题/内容的指令。
     * 返回带 diary_title / diary_content 的 action；没命中返回 null。
     */
    private fun extractDiaryCreation(text: String): ChatClientAction? {
        // 必须先提到「日记」+ 创建/写/记 之一
        if (!text.contains("日记")) return null
        if (!listOf("创建", "写", "记", "新建").any { text.contains(it) }) return null

        // 先提取内容，再提取标题（避免标题贪婪匹配吃掉「内容是...」）
        val contentRegex = Regex("(?:内容是|内容叫|正文是)(.+)")
        val content = contentRegex.find(text)?.groupValues?.get(1)?.trim()

        // 标题：在「内容是」之前的文本里找
        val titleText = if (contentRegex.containsMatchIn(text)) {
            text.substring(0, contentRegex.find(text)!!.range.first)
        } else {
            text
        }
        val titleRegex = Regex("(?:叫|标题是|标题叫|名称是|叫《)《?([\\u4e00-\\u9fa5A-Za-z0-9_\\-]{1,20})》?")
        val title = titleRegex.find(titleText)?.groupValues?.get(1)

        // 都没提取到 → 不算「带参创建」，走普通日记跳转
        if (title.isNullOrBlank() && content.isNullOrBlank()) return null

        val today = java.time.LocalDate.now().toString()
        val payload = mutableMapOf("diary_date" to today)
        if (!title.isNullOrBlank()) payload["diary_title"] = title
        if (!content.isNullOrBlank()) payload["diary_content"] = content
        return ChatClientAction("open_diary_editor", payload)
    }

    /**
     * 识别「把昵称改成小团」「改签名 XXX」「改生日 2000-01-01」这类修改资料的指令。
     * 返回带字段和值的 action；没命中返回 null。
     */
    private fun extractProfileUpdate(text: String): ChatClientAction? {
        if (!listOf("改", "修改", "换成", "变为").any { text.contains(it) }) return null

        // 昵称（分支按长度从长到短排，避免「改」抢先匹配「改成」）
        Regex("(?:把)?昵称(?:改成叫|改成|改为|改叫|改)?[是为]?([\\u4e00-\\u9fa5A-Za-z0-9_]{1,15})")
            .find(text)?.let {
                return ChatClientAction("update_profile", mapOf("field" to "nickname", "value" to it.groupValues[1]))
            }
        // 签名
        Regex("(?:把)?签名(?:改成叫|改成|改为|改叫|改)?[是为]?(.{1,50})")
            .find(text)?.let {
                return ChatClientAction("update_profile", mapOf("field" to "signature", "value" to it.groupValues[1].trim()))
            }
        // 生日（格式 2000-01-01 / 2000年1月1日）
        Regex("(?:把)?生日(?:改成|改为|改)?[是为]?(\\d{4}[-年]\\d{1,2}[-月]\\d{1,2}日?)")
            .find(text)?.let {
                val raw = it.groupValues[1]
                    .replace("年", "-").replace("月", "-").replace("日", "")
                return ChatClientAction("update_profile", mapOf("field" to "birthday", "value" to raw))
            }
        // 性别
        Regex("(?:把)?性别(?:改成|改为|改)?[是为]?(男|女|保密)")
            .find(text)?.let {
                return ChatClientAction("update_profile", mapOf("field" to "gender", "value" to it.groupValues[1]))
            }
        return null
    }

    /** 从文本中提取白噪音声音名称（「播放水滴声」→ 水滴） */
    private fun extractSoundName(text: String): String? {
        // 常见声音名列表（按优先级排序）
        val soundNames = listOf(
            "水滴", "雨声", "鸟鸣", "篝火", "海浪", "森林", "风声", "雷声",
            "猫咪呼噜", "猫叫", "钢琴", "白噪音", "粉红噪音", "棕噪音",
            "大雨", "小雨", "毛毛雨", "雷暴", "瀑布", "河流", "湖泊",
            "田野", "丛林", "咖啡厅", "图书馆", "办公室", "厨房",
            "飞机", "火车", "地铁", "人群", "交通"
        )
        return soundNames.firstOrNull { text.contains(it) }
    }

    /**
     * 匹配「打开/跳转 第 N 个链接」这类指代词。
     * 返回要跳转的索引（0-based），没命中返回 null。
     * 中文数字 一二三四五六七八九十 → 0-9，「最后一个」→ 负数调用方再处理。
     */
    fun matchLinkJump(raw: String, totalCount: Int): Int? {
        val text = compact(raw)
        if (totalCount <= 0) return null

        // 必须有「打开/跳转/点/进/去」之一开头或中间，避免纯聊天被误判
        if (!text.contains(Regex("(打开|请打开|帮我打开|跳转|跳到|进入|点|点开|点进|进|去)"))) return null
        // 必须有「链接/条/个/结果」之一
        if (!text.contains(Regex("(链接|条|个|结果|内容|网址|刚才)"))) return null
        if (text.length > 40) return null

        // 最后一个
        if (text.contains(Regex("(最后一个|最后条|倒数)"))) {
            return (totalCount - 1).coerceAtLeast(0)
        }

        // 刚才/那个/第一个（短指代词默认第一个）
        if (text.contains(Regex("(刚才|那个|刚才的链接|第一个|首条|第一条)"))) {
            return 0
        }

        // 第 N 个 / 第 N 条
        val cnDigit = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
        val m = Regex("第([一二两三四五六七八九十\\d]+)(个|条)").find(text)
        if (m != null) {
            val rawIdx = m.groupValues[1]
            val n = if (rawIdx[0].isDigit()) {
                rawIdx.toIntOrNull() ?: 0
            } else {
                // 简单中文数字：「十」→10，「十二」→12，「二十」→20
                if (rawIdx == "十") 10
                else if (rawIdx.startsWith("十")) {
                    10 + (cnDigit[rawIdx[1]] ?: 0)
                } else if (rawIdx.length == 2 && rawIdx[1] == '十') {
                    (cnDigit[rawIdx[0]] ?: 0) * 10
                } else {
                    rawIdx.map { cnDigit[it] ?: 0 }.reduce { acc, i -> acc * 10 + i }
                }
            }
            val idx = (n - 1).coerceAtLeast(0)
            if (idx < totalCount) return idx
        }
        return null
    }
}
