package com.example.mydiary.live2d

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.models.ChatConfirmation
import com.example.mydiary.data.models.ChatRequest
import com.example.mydiary.data.models.ChatStreamEvent
import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.di.AuthInterceptor
import com.example.mydiary.ui.chat.ChatClientAction
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * - 点一下小猫 → 语音听你说 → 走聊天接口 → 气泡显示回复 + TTS 念出来（服务老人/视障用户）
 */
@AndroidEntryPoint
class PetOverlayService : Service() {

    companion object {
        private const val TAG = "PetOverlayService"
        private const val CAT_SIZE_DP = 110
        private const val BUBBLE_WIDTH_DP = 190
        private const val DRAG_SLOP_DP = 8
        private const val MODEL_PATH = "live2d/Tororo/tororo.model3.json"

        /** 当前运行中的桌宠实例（Live2D 页独占渲染协作用） */
        @Volatile
        private var instance: PetOverlayService? = null

        fun startIntent(context: Context): Intent = Intent(context, PetOverlayService::class.java)

        /**
         * 桌宠收到的「操作 App」指令（后端 client_action 事件，或本地关键词兜底）。
         * MainActivity 订阅它做真实导航，让「帮我写日记」这类话能直接跳到对应页面。
         */
        private val _clientActions = MutableSharedFlow<ChatClientAction>(extraBufferCapacity = 8)
        val clientActions: SharedFlow<ChatClientAction> = _clientActions

        /** 指令命中后团子说的确认语（避免把后端的「**完成**」直接念出来） */
        private fun confirmationFor(action: String): String = when (action) {
            "open_diary_editor" -> "好的，帮你打开日记本啦～喵"
            "create_diary" -> "好的，这就帮你记下来～喵"
            "open_todo_create" -> "好的，待办给你打开啦～喵"
            "open_white_noise" -> "好呀，白噪音给你安排上～喵"
            "play_white_noise" -> "好呀，这就给你播放～喵"
            "stop_white_noise" -> "好，停了～喵"
            "open_test_hub", "open_test" -> "好呀，心理测评在这儿～喵"
            "open_drawing" -> "好呀，一起来画画～喵"
            "open_calendar" -> "好，回日历啦～喵"
            "open_chat" -> "来聊天吧～喵"
            "open_pomodoro" -> "好呀，开始专注～喵"
            "open_highlights" -> "看看你的高光时刻～喵"
            "open_little_joys" -> "看看你的小确幸～喵"
            "open_growth_portrait" -> "看看你的成长画像～喵"
            "open_achievement" -> "看看你的成就～喵"
            "open_shop" -> "商城在这里～喵"
            "open_live2d" -> "Live2D 来啦～喵"
            "open_profile" -> "你的资料页～喵"
            "update_profile" -> "好的，帮你改～喵"
            "open_diary_search" -> "好，去搜日记～喵"
            "open_explore" -> "带你去探索～喵"
            else -> "好的，帮你打开啦～喵"
        }

        /**
         * Live2D 页打开/关闭时通知桌宠：两个 Live2DView 必须独占渲染、永不同时画。
         * 共享 GL 程序对象的 uniform 是全局状态，双视图并发渲染会互相污染矩阵
         * （表现为模型随机闪烁变形），因此页打开时隐藏并暂停桌宠，关闭再恢复。
         */
        fun setLive2DPageActive(active: Boolean) {
            instance?.onLive2DPageActiveChanged(active)
        }

        /**
         * 最近一次搜索结果（链接跳转指令用）。存 companion 让 App 任何地方都能访问，
         * 跳转时 Intent.ACTION_VIEW 需要 NEW_TASK flag（Service 里启 Activity 必须加）。
         */
        @Volatile var recentSearchResults: List<com.example.mydiary.data.models.SocialSearchItem>? = null
        @Volatile var recentSearchKeyword: String? = null
        @Volatile var recentSearchLocked: Boolean = false

        /**
         * 团子「创建日记」指令的预填数据（标题/内容），DiaryEditorScreen 启动时读取并清空。
         */
        @Volatile var pendingDiaryTitle: String? = null
        @Volatile var pendingDiaryContent: String? = null

        /**
         * 团子「修改资料」指令的目标字段和值，EditProfileScreen 启动时读取并清空。
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

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var catView: Live2DView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    /** 打字聊天（语音服务连不上时的兜底通道） */
    private var inputRow: LinearLayout? = null
    private var inputEdit: EditText? = null
    private var inputVisible = false
    private var inputRestY = 0
    private var busyRetried = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 当前正在播放 TTS 的 MediaPlayer，打断时需要停止 */
    private var currentTtsPlayer: android.media.MediaPlayer? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isChatting = false

    // ===== 常听模式：一直听，只有喊「团子」才应答 =====
    @Volatile private var wakeListening = false

    /** 团子正在念话：必须让麦克风闭嘴，否则会把自己的声音当成唤醒词（自触发） */
    @Volatile private var ttsSpeaking = false

    /** 唤醒后等待指令的截止时间（SystemClock.uptimeMillis），0 = 不在指令窗口 */
    private var awaitCommandUntil = 0L

    // ===== 高精度离线语音识别（Sherpa-ONNX + SenseVoice） =====
    private var sherpaEngine: SherpaAsrEngine? = null

    /** Live2D 页是否正在显示（桌宠隐藏中） */
    private var live2DPageActive = false

    private val greetings = listOf(
        "喵～我是团子，喊一声「团子」，我就在这儿听着呢",
        "今天过得怎么样呀？喵～",
        "喵呜～把坏心情交给我，我帮你吃掉！",
        "喵～喊「团子」，我就应你，一直听着哦",
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "桌宠服务启动")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initTts()
        showCat()
        // 先准备离线耳朵，模型就绪后再问候；失败也照常问候（此时只能打字聊）
        mainHandler.postDelayed({ showBubble("团子正在叫醒耳朵……喵") }, 400)
        initSherpaEngine()
    }

    /** 初始化高精度离线语音识别引擎（Sherpa-ONNX + SenseVoice） */
    private fun initSherpaEngine() {
        sherpaEngine = SherpaAsrEngine(this).apply {
            setCallback(object : SherpaAsrEngine.Callback {
                override fun onReady() {
                    mainHandler.post {
                        speak(greetings.random())
                        maybeStartWakeListening()
                    }
                }

                override fun onSegment(text: String) {
                    mainHandler.post { onSherpaHeard(text) }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Sherpa 引擎出错: $message")
                    mainHandler.post {
                        speak("耳朵出了点小问题，用打字跟我说吧～")
                        toggleTextInput(true)
                    }
                }
            })
            prepare()
        }
    }

    /** Sherpa 听到一段语音：手动模式直接当聊天，常听模式检查唤醒词 */
    private fun onSherpaHeard(text: String) {
        Log.i(TAG, "Sherpa 听到: $text")
        // 打断：团子在念话或聊天时，听到「停下」「团子」立即停止
        if (ttsSpeaking || isChatting) {
            if (isInterruptWord(text)) {
                interruptCurrent()
                return
            }
        }
        if (isListening) {
            isListening = false
            hideBubble()
            listenFailCount = 0
            sendToChat(text)
            return
        }
        onWakeHeard(text)
    }

    /** 打断词检查：「停下」「团子」都算 */
    private fun isInterruptWord(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        return compact.contains("停下") || compact.contains("团子")
    }

    /** 打断当前说话/聊天：立即停止 TTS 和请求，恢复常听 */
    private fun interruptCurrent() {
        Log.i(TAG, "用户打断当前操作")
        // 停止 TTS 播放
        ttsSpeaking = false
        currentTtsPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        currentTtsPlayer = null
        // 取消聊天请求
        isChatting = false
        // 恢复常听
        maybeStartWakeListening()
        // 简短确认
        speak("好的，停了")
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
                    Log.d(TAG, "团子选用音色: ${preferred.name}")
                } else {
                    Log.w(TAG, "本机无本地普通话音色，使用引擎默认音色（可能需要联网下载语音数据）")
                }
                // 不再做变调（pitch 变化会在 embedded 声线上产生金属电音感），只轻微放慢语速
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.97f)
                // 念话期间暂停常听，念完再接着听（避免团子听自己说话而自触发）
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ttsSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        ttsSpeaking = false
                        // 留 0.5 秒等喇叭里的尾音散干净，免得团子听见自己最后半句
                        mainHandler.postDelayed({ maybeStartWakeListening() }, 500)
                    }

                    @Deprecated("系统回调必实现")
                    override fun onError(utteranceId: String?) {
                        ttsSpeaking = false
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
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("团子回复", txt))
                    android.widget.Toast.makeText(this@PetOverlayService, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
            // 点一下气泡也能手动隐藏（长回复看不完时方便收起）
            setOnClickListener { hideBubble() }
        }

        // 打字聊天兜底（语音服务连不上时用）：输入行 + 入口按钮
        val edit = EditText(this).apply {
            hint = "跟团子说点什么…"
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

        val typeEntry = TextView(this).apply {
            text = "⌨ 打字聊"
            textSize = 11f
            setTextColor(Color.argb(200, 95, 115, 105))
            setPadding(0, (2 * density).toInt(), 0, (5 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleTextInput(!inputVisible) }
        }

        // 小猫 Live2D 视图（复用现有渲染栈，按最长边贴合居中）
        val cat = Live2DView(this).apply {
            layoutParams = LinearLayout.LayoutParams(catSize, catSize)
        }
        catView = cat

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
            addView(typeEntry)
            addView(cat)
        }
        rootView = root
        bubbleText = bubble

        // 拖动 + 单击说话（须同时挂在根布局和小猫视图上：
        // GLSurfaceView 会自行消费触摸，只挂根布局会收不到小猫区域的触摸）
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downAt = 0L
        val slop = (DRAG_SLOP_DP * density).toInt()

        val petTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.i(TAG, "cat ACTION_DOWN raw=(${event.rawX},${event.rawY})")
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = windowParams?.x ?: 0
                    startY = windowParams?.y ?: 0
                    downAt = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        windowParams?.let {
                            it.x = startX + dx
                            it.y = startY + dy
                            windowManager.updateViewLayout(root, it)
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
        // root 和 cat 都挂：确保 NOT_FOCUSABLE 状态下 adb 触摸一定能到达悬浮窗
        rootView?.setOnTouchListener(petTouchListener)
        cat.setOnTouchListener(petTouchListener)
        Log.i(TAG, "petTouchListener attached: rootView=${rootView != null} cat=${cat != null}")

        // 初始位置：屏幕右下角
        // NOT_FOCUSABLE：必须设，否则悬浮窗会抢输入法焦点被 WindowManager 隐藏（isOnScreen=false）
        // NOT_TOUCH_MODAL：悬浮窗外的触摸透传给下层 Activity
        // WATCH_OUTSIDE_TOUCH：关键！让窗口即使 NOT_FOCUSABLE，也能接收触摸事件（含悬浮窗内部的触摸）
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - catSize - (12 * density).toInt()
            y = dm.heightPixels - catSize - (220 * density).toInt()
        }
        windowParams = params
        windowManager.addView(root, params)

        // Surface 就绪后加载模型（与全屏页同样的延迟策略）
        mainHandler.postDelayed({
            cat.loadModel(MODEL_PATH)
        }, 400)
    }

    /** Live2D 页打开时隐藏并暂停渲染，关闭后恢复（主线程执行；onPause 会阻塞到 GL 线程真正停住） */
    private fun onLive2DPageActiveChanged(active: Boolean) {
        mainHandler.post {
            val root = rootView ?: return@post
            if (live2DPageActive == active) return@post
            live2DPageActive = active
            if (active) {
                stopWakeListening() // Live2D 页占屏时桌宠隐藏，没必要听
                root.visibility = View.GONE
                catView?.onPause()
                // 桌宠 GL 线程已完全停止，重置着色器单例，让 Live2D 页的上下文重建程序
                CubismRendererAndroid.reloadShader()
            } else {
                root.visibility = View.VISIBLE
                // 恢复渲染会重建 Surface → onSurfaceCreated 里重置着色器并重载模型
                catView?.onResume()
                maybeStartWakeListening()
            }
        }
    }

    /** 点小猫：开始听你说话 */
    private fun onTapCat() {
        if (isChatting) return
        if (inputVisible) toggleTextInput(false)
        if (isListening) {
            stopListening()
            return
        }
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            speak("还没有麦克风权限哦，允许后再点我说话吧")
            return
        }
        startListening()
    }

    private fun startListening() {
        if (isListening) return
        stopWakeListening() // 手动听时先让出麦克风
        if (sherpaEngine == null) {
            speak("团子还在叫醒耳朵，等我几秒再点一下哦")
            return
        }
        startSherpaListening()
    }

    /** 连续听不到声音的次数；连续两次就直接引导用户打字，避免一直卡在「没听清」 */
    private var listenFailCount = 0
    private fun onListenMissed() {
        listenFailCount++
        if (listenFailCount >= 2) {
            listenFailCount = 0
            Log.w(TAG, "连续两次未接收到声音，改用打字引导（请检查设备麦克风是否可用）")
            speak("我这边好像没接收到声音，先用打字跟我说吧～")
            toggleTextInput(true)
        } else {
            speak("我没听清，再点我说一遍好不好")
        }
    }

    /** Sherpa 手动聆听：点一下猫，下一段语音直接当聊天内容 */
    private fun startSherpaListening() {
        if (isListening) return
        val engine = sherpaEngine ?: return
        isListening = true
        showBubble("我在听……喵")
        engine.start()
        // 15 秒没识别到内容自动结束
        mainHandler.postDelayed({
            if (isListening) {
                isListening = false
                engine.stop()
                onListenMissed()
            }
        }, 15000)
    }

    // ===== 常听模式：一直听，只有喊「团子」才应答 =====

    /** 现在能不能开常听：手动在听、正在聊天、正在念话、打字框开着、Live2D 页占屏时都不听 */
    private fun canListenNow(): Boolean =
        sherpaEngine != null && !isListening && !isChatting && !ttsSpeaking &&
            !inputVisible && !live2DPageActive

    private fun maybeStartWakeListening() {
        if (canListenNow()) startWakeListening()
    }

    private fun startWakeListening() {
        if (wakeListening || !canListenNow()) return
        val engine = sherpaEngine ?: return
        engine.start()
        wakeListening = true
        Log.i(TAG, "常听模式已开启")
    }

    private fun stopWakeListening() {
        if (!wakeListening) return
        wakeListening = false
        sherpaEngine?.stop()
    }

    /** 常听听到一句话：没喊「团子」就当没听见；喊了就应答 */
    private fun onWakeHeard(text: String) {
        if (!canListenNow()) return
        if (PetIntentRouter.containsWakeWord(text)) {
            val rest = PetIntentRouter.stripWakeWord(text).orEmpty()
            awaitCommandUntil = 0L
            Log.i(TAG, "常听唤醒: $text")
            if (rest.isNotBlank()) {
                // 「团子，打开日记」一口气说完
                sendToChat(rest)
            } else {
                // 只喊了声名字，开 10 秒指令窗口
                awaitCommandUntil = android.os.SystemClock.uptimeMillis() + 10_000
                speak("我在听，说吧～")
            }
            return
        }
        if (awaitCommandUntil > 0L && android.os.SystemClock.uptimeMillis() < awaitCommandUntil) {
            awaitCommandUntil = 0L
            Log.i(TAG, "指令窗口收到: $text")
            sendToChat(text)
            return
        }
        Log.d(TAG, "常听忽略（没喊团子）: $text")
    }

    /** 兜底：系统 Google 语音识别（需要联网，多数模拟器环境会失败） */
    private fun startGoogleListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("这台设备没有语音识别，用打字跟我说吧～")
            toggleTextInput(true)
            return
        }
        isListening = true
        showBubble("我在听……喵")
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(recognitionListener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
        // 15 秒没说话自动收起
        mainHandler.postDelayed({ if (isListening) stopListening() }, 15000)
    }

    private fun stopListening() {
        isListening = false
        sherpaEngine?.stop()
        runCatching { recognizer?.stopListening() }
        if (bubbleText?.visibility == View.VISIBLE && bubbleText?.text == "我在听……喵") {
            hideBubble()
        }
        maybeStartWakeListening() // 手动听结束后恢复常听
    }

    /** 打开/收起打字输入框；打开时临时变可聚焦（让输入法弹出），收起后恢复 NOT_FOCUSABLE（保证悬浮窗 visible） */
    private fun toggleTextInput(show: Boolean) {
        val root = rootView ?: return
        val params = windowParams ?: return
        if (inputVisible == show) return
        if (show && root.visibility != View.VISIBLE) return
        inputVisible = show
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            hideBubble()
            inputRow?.visibility = View.VISIBLE
            inputRestY = params.y
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.y = (140 * resources.displayMetrics.density).toInt()
            windowManager.updateViewLayout(root, params)
            mainHandler.postDelayed({
                val edit = inputEdit ?: return@postDelayed
                edit.requestFocus()
                runCatching { imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
            }, 150)
        } else {
            runCatching { inputEdit?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) } }
            inputEdit?.clearFocus()
            inputRow?.visibility = View.GONE
            // 恢复 NOT_FOCUSABLE，否则悬浮窗会抢输入法焦点被 WindowManager 隐藏
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.y = inputRestY
            windowManager.updateViewLayout(root, params)
            maybeStartWakeListening() // 收起打字框后恢复常听
        }
    }

    /** 发送打字内容（走和小嘉然一样的聊天通道） */
    private fun sendInputText() {
        val raw = inputEdit?.text?.toString()
        val text = raw?.trim().orEmpty()
        Log.i(TAG, "sendInputText: raw='$raw' trimmed='$text'")
        if (text.isEmpty()) {
            Log.w(TAG, "sendInputText: text empty, abort (hint=${inputEdit?.hint})")
            return
        }
        inputEdit?.setText("")
        toggleTextInput(false)
        sendToChat(text)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: android.os.Bundle) {
            isListening = false
            val spoken = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (spoken.isNullOrBlank()) {
                speak("我没听清，再点我说一遍好不好")
            } else {
                sendToChat(spoken)
            }
        }

        override fun onError(error: Int) {
            isListening = false
            Log.w(TAG, "语音识别错误: $error")
            // 出错后销毁重建识别器，防止服务卡在忙状态导致后续一直失败
            runCatching { recognizer?.destroy() }
            recognizer = null
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    speak("我没听清，再点我说一遍好不好")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    speak("需要麦克风权限才能听你说话哦")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (!busyRetried) {
                        busyRetried = true
                        showBubble("耳朵有点忙，我缓一下再听……喵")
                        mainHandler.postDelayed({ if (!isChatting && !inputVisible) startListening() }, 1500)
                    } else {
                        speak("耳朵有点忙，稍后再点我试试喵")
                    }
                }
                // 网络类错误：Google 语音服务连不上（模拟器/无外网环境常态），自动转打字
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER -> {
                    speak("网络连不上语音服务，用打字跟我说吧～")
                    toggleTextInput(true)
                }
                else -> speak("耳朵有点忙，稍后再试试喵")
            }
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) { busyRetried = false }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { showBubble("让我想想……喵") }
        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    /**
     * 本地就能办的指令：直接跳页面，或引导用户说清要打开什么。处理掉了返回 true。
     */
    private fun routeLocalCommand(text: String): Boolean {
        // 链接跳转指代词（「打开第一个链接」等）交给 sendToChat 里的 handleLinkJump 处理，
        // 别被 looksLikeOpenOnly 拦截成「只听到打开没目标」的引导语。
        if (PetIntentRouter.matchLinkJump(text, 1) != null) return false
        PetIntentRouter.match(text)?.let { action ->
            Log.d(TAG, "本地指令命中: ${action.action} payload=${action.payload}")
            _clientActions.tryEmit(action)
            speak(confirmationFor(action.action))
            return true
        }
        // 只听到「打开」却没说要打开什么：别当普通聊天乱答，直接问清楚
        if (PetIntentRouter.looksLikeOpenOnly(text)) {
            Log.d(TAG, "只听到「打开」没有目标，回引导语")
            awaitCommandUntil = android.os.SystemClock.uptimeMillis() + 10_000
            speak(PetIntentRouter.OPEN_PROMPT)
            return true
        }
        return false
    }

    /** 把你说的话发给聊天接口，流式收回复 */
    private fun sendToChat(text: String) {
        // 唤醒词常被连在句子里（「团子 打开日记」），先摘掉再判断
        val cleanText = PetIntentRouter.stripWakeWord(text)?.takeIf { it.isNotBlank() } ?: text
        val userId = sessionManager.getUserId()
        Log.i(TAG, "sendToChat called: text='$cleanText' userId=$userId")
        if (userId == null || userId <= 0L) {
            speak("请先登录，再回来找我玩哦")
            maybeStartWakeListening()
            return
        }
        // 本地兜底：不依赖网络的指令识别，命中就直接跳页面（零延迟，也省一次请求）
        if (routeLocalCommand(cleanText)) {
            maybeStartWakeListening()
            return
        }

        // 链接跳转：对上一次搜索结果说「打开第一个/链接」——零延迟本地处理
        val cachedResults = recentSearchResults
        if (cachedResults != null) {
            val idx = PetIntentRouter.matchLinkJump(cleanText, cachedResults.size)
            if (idx != null) {
                val item = cachedResults[idx]
                Log.i(TAG, "跳转链接: index=$idx url=${item.url}")
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure { Log.e(TAG, "跳转链接失败", it) }
                val platform = when (item.platform) {
                    "douyin" -> "抖音"; "bilibili" -> "B站"; "zhihu" -> "知乎"
                    "kuaishou" -> "快手"; "weibo" -> "微博"; "netease" -> "网易云"
                    else -> "小红书"
                }
                speak("好的，帮你打开第 ${idx + 1} 条《${item.title}》，来自${platform}～喵")
                maybeStartWakeListening()
                return
            }
        } else if (PetIntentRouter.matchLinkJump(cleanText, 1) != null) {
            // 说了链接跳转但没缓存 → 提示先搜
            speak("还没有搜索结果哦，先搜一下你想看的内容吧～喵")
            maybeStartWakeListening()
            return
        }

        stopWakeListening() // 聊天期间让出麦克风
        isChatting = true
        showBubble("思考中……喵")
        // 关闭深度思考时模型经常偷懒不调搜索工具（项目已知问题）；对明确的搜索意图
        // 单独打开深度思考换工具触发率，普通闲聊仍保持快速通道
        val wantsSearch = Regex("(搜索|搜一下|搜一搜|帮我找|找一下|找找|查一下|查查)").containsMatchIn(cleanText)
        Log.i(TAG, "准备发网络请求: cleanText='$cleanText' deepThinking=$wantsSearch api=${ApiEndpoints.apiBaseUrl}")
        Thread {
            Log.i(TAG, "Thread 开始执行网络请求")
            try {
                val conversationId = "conv_${userId}_pet"
                val client = okHttpClient.newBuilder().apply {
                    interceptors().clear()
                    networkInterceptors().clear()
                    addInterceptor(authInterceptor)
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                }.build()

                val sb = StringBuilder()
                var actionConfirm: String? = null
                var searchItems: List<com.example.mydiary.data.models.SocialSearchItem>? = null
                var searchKeyword: String? = null
                var searchLocked = false
                var pendingPlanId: String? = null
                var pendingPlanTitle: String? = null

                fun handleEvent(event: ChatStreamEvent) {
                    when (event.type) {
                        "delta" -> sb.append(event.delta.orEmpty())
                        "reply" -> {
                            // 完整回复事件：以它为准（后端会在结尾发）
                            event.reply?.takeIf { it.isNotBlank() }?.let { sb.clear(); sb.append(it) }
                        }
                        "error" -> throw IllegalStateException(event.error.orEmpty().ifBlank { "回复失败" })
                        "client_action" -> {
                            // 后端判定的「操作 App」指令：转给 MainActivity 去跳真实页面
                            val act = event.action.orEmpty()
                            if (act.isNotBlank()) {
                                Log.d(TAG, "后端指令命中: $act payload=${event.payload}")
                                _clientActions.tryEmit(ChatClientAction(act, event.payload.toStringMap()))
                                actionConfirm = confirmationFor(act)
                            }
                        }
                        "tool_plan" -> {
                            // 付费操作（如搜索）的待确认计划：先记下来，流结束后由团子代确认
                            if (event.requiresConfirmation != false && !event.planId.isNullOrBlank()) {
                                pendingPlanId = event.planId
                                pendingPlanTitle = event.title ?: event.tool
                                Log.d(TAG, "收到待确认计划: planId=${event.planId} tool=${event.tool}")
                            }
                        }
                        "search_results" -> {
                            // 小嘉然返回的搜索结果：保存起来等会读给用户听，同时写入 companion 让后续链接跳转能访问
                            if (event.locked == true) {
                                searchLocked = true
                                recentSearchResults = null
                                recentSearchKeyword = null
                                recentSearchLocked = true
                                Log.w(TAG, "搜索被锁定（未绑密钥或余额不足）: ${event.errorCode}")
                            } else {
                                searchItems = event.items
                                searchKeyword = event.keyword
                                // companion 缓存让"打开第 N 个链接"这类后续指令能直接跳转
                                recentSearchResults = event.items
                                recentSearchKeyword = event.keyword
                                recentSearchLocked = false
                            }
                            Log.d(TAG, "收到搜索结果: keyword=${event.keyword}, ${event.items?.size ?: 0} 条, locked=${event.locked}")
                        }
                    }
                }

                // 第一轮：把用户的话发给小嘉然
                if (wantsSearch) Log.d(TAG, "检测到搜索意图，本条打开深度思考以稳定触发搜索工具")
                streamChat(
                    client,
                    ChatRequest(userId = userId, message = cleanText, conversationId = conversationId, deepThinking = wantsSearch),
                    ::handleEvent,
                )

                // 有待确认计划 → 团子替用户确认（视障用户没法点确认卡片，不能卡在这一步）
                val planId = pendingPlanId
                if (planId != null) {
                    Log.i(TAG, "团子自动确认计划: $planId ($pendingPlanTitle)")
                    mainHandler.post { showBubble("小嘉然问要不要继续，我帮你确认啦～喵") }
                    streamChat(
                        client,
                        ChatRequest(
                            userId = userId,
                            message = "",
                            conversationId = conversationId,
                            confirmation = ChatConfirmation(planId = planId, approved = true),
                        ),
                        ::handleEvent,
                    )
                }
                val reply = sb.toString().trim()
                mainHandler.post {
                    isChatting = false
                    val confirm = actionConfirm
                    val items = searchItems
                    val locked = searchLocked
                    when {
                        // 已经帮你把页面打开了，就只报一句确认语（别念后端的「**完成**」）
                        confirm != null -> speak(confirm)
                        // 有搜索结果：气泡里放标题+链接，嘴里只念标题和平台（网址念出来没用，还容易把 TTS 念崩）
                        !items.isNullOrEmpty() -> {
                            fun platformName(code: String?) = when (code) {
                                "douyin" -> "抖音"; "bilibili" -> "B站"; "zhihu" -> "知乎"
                                "kuaishou" -> "快手"; "weibo" -> "微博"; "netease" -> "网易云"
                                else -> "小红书"
                            }
                            val keywordText = searchKeyword?.let { "关于「$it」" } ?: ""
                            val bubbleSb = StringBuilder("$keywordText 找到 ${items.size} 条结果：")
                            val speakSb = StringBuilder("$keywordText 找到 ${items.size} 条结果：")
                            items.take(3).forEachIndexed { index, item ->
                                bubbleSb.append("第${index + 1}条，${item.title}，来自${platformName(item.platform)}，链接是 ${item.url}")
                                speakSb.append("第${index + 1}条，${item.title}，来自${platformName(item.platform)}。")
                            }
                            if (items.size > 3) {
                                bubbleSb.append("，还有 ${items.size - 3} 条，想听更多跟我说")
                                speakSb.append("，还有 ${items.size - 3} 条，链接我都放在气泡里了，想听更多跟我说")
                            }
                            speak(speakSb.toString())
                            showBubble(bubbleSb.toString())
                        }
                        // 搜索被锁（没绑密钥/没余额）且小嘉然没给文字说明时，给个明确提示
                        locked && reply.isBlank() ->
                            speak("搜索功能还没开通哦，去账户页看看密钥和余额吧～喵")
                        reply.isBlank() -> speak("这条我没想好怎么回答，换个说法试试喵")
                        else -> {
                            showBubble(reply)
                            speak(reply)
                        }
                    }
                    maybeStartWakeListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "桌宠对话失败", e)
                mainHandler.post {
                    isChatting = false
                    speak("网络开小差了，稍后再试试喵")
                    maybeStartWakeListening()
                }
            }
        }.start()
    }

    /** 发起一次 chat/stream 请求，把每个 SSE 事件交给 handler；HTTP 非 200 或后端 error 事件会抛异常 */
    private fun streamChat(
        client: OkHttpClient,
        request: ChatRequest,
        handler: (ChatStreamEvent) -> Unit,
    ) {
        val url = "${ApiEndpoints.apiBaseUrl}chat/stream"
        Log.i(TAG, "streamChat POST $url body=${gson.toJson(request).take(100)}")
        val body = gson.toJson(request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder()
            .url("${ApiEndpoints.apiBaseUrl}chat/stream")
            .post(body)
            .header("Accept", "text/event-stream")
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val source = response.body?.source() ?: throw IllegalStateException("空回复")
            var lineCount = 0
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) continue
                val event = runCatching { gson.fromJson(payload, ChatStreamEvent::class.java) }.getOrNull() ?: continue
                handler(event)
                lineCount++
            }
            Log.d(TAG, "SSE 流结束，共 $lineCount 个事件")
        }
    }

    /** 气泡自动隐藏延迟（毫秒）。30 秒足够用户看清长搜索结果，点击气泡可手动提前收起 */
    private val BUBBLE_AUTO_HIDE_MS = 30_000L

    private fun showBubble(text: String) {
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
        showBubble(text)
        stopWakeListening() // 念话期间闭嘴，否则团子会听见自己的声音而自触发
        ttsSpeaking = true

        // 从后端 /tts 取 edge-tts 真人声播放，替代系统 TTS
        playServerTts(text)

        // showBubble 已经管了 timer（30 秒），这里无需重复 post
    }

    /** 从后端 /tts 拉取 edge-tts 真人语音并流式播放 */
    private fun playServerTts(text: String) {
        Thread {
            var mp: android.media.MediaPlayer? = null
            try {
                val base = com.example.mydiary.data.network.ApiEndpoints.apiBaseUrl // http://10.0.2.2:8000/
                val url = base + "tts?text=" + java.net.URLEncoder.encode(text, "UTF-8")

                mp = android.media.MediaPlayer()
                currentTtsPlayer = mp  // 保存引用，打断时能停止
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    ttsSpeaking = false
                    currentTtsPlayer = null
                    it.release()
                    maybeStartWakeListening()   // 念完再开唤醒
                }
                mp.setOnErrorListener { _, _, _ ->
                    ttsSpeaking = false
                    currentTtsPlayer = null
                    mp?.release()
                    maybeStartWakeListening()   // 失败也让出麦克风
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                ttsSpeaking = false
                currentTtsPlayer = null
                mp?.release()
                maybeStartWakeListening()
            }
        }.start()
    }

    private val autoHideRunnable = Runnable { if (!isListening && !isChatting) hideBubble() }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopWakeListening()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { rootView?.let { windowManager.removeView(it) } }
        catView?.release()
        catView = null
        rootView = null
        bubbleText = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        sherpaEngine?.release()
        sherpaEngine = null
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
    private val wakeWords = listOf("小团子", "团子", "团仔", "团紫", "团纸", "团只", "弹子", "坛子")

    /** 只喊了「打开」没说要开什么时，团子这么问 */
    const val OPEN_PROMPT = "要打开哪个呀？日记、待办、白噪音还是画画？说一个就行～喵"

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
