package com.example.mydiary.ui.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.DiaryMultimodalInsightResponse
import com.example.mydiary.data.models.EmotionAnalysis
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.data.models.VoiceTranscription
import com.example.mydiary.data.models.toDomain
import com.example.mydiary.data.models.toVoiceTranscription
import com.example.mydiary.data.models.VoiceProcessWithOptionsResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.DrawingRepository
import com.example.mydiary.data.repository.GrowthPortraitRepository
import com.example.mydiary.data.repository.MediaRepository
import com.example.mydiary.data.repository.UserRepository
import com.example.mydiary.data.repository.VoiceRepository
import com.example.mydiary.ui.drawing.DrawingNavigationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

private const val TAG = "DiaryEditorVMDebug"

/**
 * 日记编辑器ViewModel
 * 管理日记编辑状态和业务逻辑
 * 
 * 需求：2.1, 2.2, 2.3, 2.4, 8.1, 9.1, 10.1, 11.1
 */
@HiltViewModel
class DiaryEditorViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val voiceRepository: VoiceRepository,
    private val mediaRepository: MediaRepository,
    private val userRepository: UserRepository,
    private val growthPortraitRepository: GrowthPortraitRepository,
    private val drawingRepository: DrawingRepository,
    private val drawingNavigationState: DrawingNavigationState
) : ViewModel() {
    companion object {
        private const val FUTURE_DIARY_DATE_MESSAGE = "日记不能记录未来的时间，已切换到今天"
    }


    private val _uiState = MutableStateFlow(DiaryEditorUiState())
    val uiState: StateFlow<DiaryEditorUiState> = _uiState.asStateFlow()

    // 订阅来自 SharedDrawingViewModel 的自动保存完成事件，匹配当前 diaryId 时刷新媒体列表
    init {
        viewModelScope.launch {
            drawingNavigationState.diaryMediaRefreshEvents.collect { refreshedDiaryId ->
                if (refreshedDiaryId == _uiState.value.diaryId) {
                    refreshMediaItems(refreshedDiaryId)
                }
            }
        }
    }
    
    // 语音处理（带选项）UI状态
    private val _voiceProcessingWithOptionsState = MutableStateFlow(VoiceProcessingWithOptionsUiState())
    val voiceProcessingWithOptionsState: StateFlow<VoiceProcessingWithOptionsUiState> = _voiceProcessingWithOptionsState.asStateFlow()

    // 绘画保存后刷新媒体项标志
    private val _shouldRefreshMedia = MutableStateFlow(false)
    val shouldRefreshMedia: StateFlow<Boolean> = _shouldRefreshMedia.asStateFlow()

    // AI 绘图功能启用状态（控制移动端显示）
    private val _drawingEnabled = MutableStateFlow(true)  // 默认为 true，确保向后兼容
    val drawingEnabled: StateFlow<Boolean> = _drawingEnabled.asStateFlow()

    fun setRefreshMediaFlag() {
        _shouldRefreshMedia.value = true
    }

    fun clearRefreshMediaFlag() {
        _shouldRefreshMedia.value = false
    }

    /**
     * 获取 AI 绘图功能的启用状态
     * 从后端获取配置，控制移动端是否显示 AI 绘图功能
     */
    fun fetchDrawingEnabled() {
        viewModelScope.launch {
            try {
                val result = drawingRepository.getDrawingEnabled()
                result.onSuccess { response ->
                    _drawingEnabled.value = response.drawing_enabled
                    Log.d(TAG, "获取 AI 绘图配置成功: drawing_enabled=${response.drawing_enabled}")
                }
                result.onFailure { e ->
                    Log.w(TAG, "获取 AI 绘图配置失败，使用默认值: ${e.message}")
                    // 失败时默认为 true，确保功能正常
                    _drawingEnabled.value = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取 AI 绘图配置异常，使用默认值: ${e.message}")
                _drawingEnabled.value = true
            }
        }
    }

    // 从绘画流程保存媒体到日记（通过服务器保存）
    fun saveDrawingMediaToDiary(diaryId: Long, mediaType: String, mediaUrl: String) {
        viewModelScope.launch {
            try {
                // 构建设置媒体URL的数据（保存到现有日记的media_items）
                val diary = Diary(
                    id = diaryId,
                    userId = getCurrentUserId(),
                    title = null,
                    date = _uiState.value.date,
                    mediaItems = emptyList(),
                    weather = _uiState.value.weather,
                    location = _uiState.value.location,
                    moodScore = _uiState.value.moodScore,
                    moodType = _uiState.value.moodType,
                    isHighlight = _uiState.value.isHighlight,
                    isLittleJoy = _uiState.value.isLittleJoy,
                    createdAt = null,
                    updatedAt = null
                )
                // 创建一个临时的MediaItem表示绘画生成的内容
                val drawingMediaItem = when (mediaType.lowercase()) {
                    "image" -> MediaItem.Image(
                        id = "drawing_${System.currentTimeMillis()}",
                        assetId = null,
                        url = mediaUrl,
                        thumbnailUrl = null,
                        order = _uiState.value.mediaItems.size,
                        createdAt = Instant.now()
                    )
                    "video" -> MediaItem.Video(
                        id = "drawing_${System.currentTimeMillis()}",
                        assetId = null,
                        url = mediaUrl,
                        thumbnailUrl = null,
                        duration = 0,
                        order = _uiState.value.mediaItems.size,
                        createdAt = Instant.now()
                    )
                    else -> return@launch
                }

                // 同步媒体项到服务器
                val currentItems = _uiState.value.mediaItems + drawingMediaItem
                val syncFlow = diaryRepository.syncMediaItems(diaryId, currentItems)
                val result = syncFlow.first { it !is ApiResult.Loading }
                if (result is ApiResult.Success) {
                    _uiState.update {
                        it.copy(mediaItems = result.data, hasUnsavedChanges = false)
                    }
                    Log.d(TAG, "绘画媒体已保存到日记: diaryId=$diaryId, mediaType=$mediaType, url=$mediaUrl")
                } else if (result is ApiResult.Error) {
                    Log.e(TAG, "保存绘画媒体失败: ${result.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存绘画媒体异常", e)
            }
        }
    }

    // 本地缓存：用于实时保存编辑内容
    private var localCache: Diary? = null
    
    // 标记是否为编辑模式（有diaryId）还是新建模式
    private var isEditMode: Boolean = false
    
    /**
     * 获取当前登录用户的 ID
     * 如果未登录则返回默认值 1L（兼容旧逻辑）
     */
    private fun getCurrentUserId(): Long {
        return userRepository.getCurrentUserId() ?: 1L
    }
    
    // 记录上次初始化的参数，防止重组时重复初始化（但参数变化时要重新初始化）
    private var lastInitializedDiaryId: Long? = null
    private var lastInitializedDate: LocalDate? = null
    
    /**
     * 初始化编辑器，加载指定日期的日记
     * 
     * 需求：2.2
     * 用户需求Priority 1: 支持通过diaryId加载特定日记
     * 
     * @param date 日记日期
     * @param diaryId 日记ID（可选，如果提供则加载特定日记进行编辑，否则创建新日记）
     */
    fun initialize(date: LocalDate, diaryId: Long? = null) {
        val resolvedDate = if (diaryId == null && date.isAfter(LocalDate.now())) {
            LocalDate.now()
        } else {
            date
        }
        val futureDateMessage = if (diaryId == null && resolvedDate != date) {
            FUTURE_DIARY_DATE_MESSAGE
        } else {
            null
        }

        // 获取当前登录用户的 ID
        val userId = getCurrentUserId()
        
        // 【防止重组导致重复初始化】同一个 diaryId/date 不重复初始化；但参数变化要重新初始化
        if (diaryId == lastInitializedDiaryId && resolvedDate == lastInitializedDate) {
            Log.d(TAG, "=== initialize() 被跳过（参数未变化）===")
            Log.d(TAG, "当前 diaryId: $diaryId, date: $resolvedDate")
            Log.w(TAG, "SKIP: diaryId=$diaryId, date=$resolvedDate")
            return
        }

        // 记录本次初始化的参数
        lastInitializedDiaryId = diaryId
        lastInitializedDate = resolvedDate
        
        // 【DEBUG日志】ViewModel收到的参数 - 使用 Log.w 确保显示
        Log.w(TAG, "=== ViewModel.initialize() 执行 ===")
        Log.w(TAG, "收到的 date: $resolvedDate")
        Log.w(TAG, "收到的 diaryId: $diaryId")
        Log.w(TAG, "当前登录用户 userId: $userId")
        Log.w(TAG, "判断模式: ${if (diaryId != null) "编辑模式" else "新建模式"}")
        
        isEditMode = diaryId != null
        
        _uiState.update { 
            it.copy(
                date = resolvedDate,
                userId = userId,
                diaryId = diaryId,
                errorMessage = futureDateMessage
            )
        }
        
        if (diaryId != null) {
            // 编辑模式：加载特定日记
            Log.w(TAG, "进入编辑模式，调用 loadDiaryById($diaryId)")
            loadDiaryById(diaryId)
        } else {
            // 新建模式：显示空白编辑器
            Log.w(TAG, "进入新建模式，调用 showEmptyEditor($resolvedDate)")
            showEmptyEditor(resolvedDate, futureDateMessage)
        }
    }
    
    /**
     * 显示空白编辑器（新建模式）
     * 
     * @param date 日记日期
     */
    private fun showEmptyEditor(date: LocalDate, errorMessage: String? = null) {
        _uiState.update { 
            it.copy(
                isLoading = false,
                diaryId = null,
                title = "",
                mediaItems = emptyList(),
                weather = null,
                location = null,
                moodScore = null,
                moodType = null,
                hasUnsavedChanges = false,
                errorMessage = errorMessage
            )
        }
        localCache = null
    }
    
    /**
     * 通过ID加载特定日记（编辑模式）
     * 
     * 用户需求Priority 1
     * 
     * @param diaryId 日记ID
     */
    private fun loadDiaryById(diaryId: Long) {
        Log.w(TAG, "=== loadDiaryById($diaryId) 开始 ===")
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                Log.w(TAG, "开始调用 diaryRepository.getDiaryById($diaryId)")
                diaryRepository.getDiaryById(diaryId).collect { result ->
                    Log.w(TAG, "getDiaryById 返回结果类型: ${result::class.simpleName}")
                    
                    when (result) {
                        is ApiResult.Success -> {
                            val diary = result.data
                            Log.w(TAG, "=== 加载日记成功 ===")
                            Log.w(TAG, "日记ID: ${diary.id}")
                            Log.w(TAG, "日记标题: ${diary.title}")
                            Log.w(TAG, "日记日期: ${diary.date}")
                            Log.w(TAG, "媒体项数量: ${diary.mediaItems.size}")
                            
                            localCache = diary
                            
                            _uiState.update { 
                                it.copy(
                                    diaryId = diary.id,
                                    title = diary.title ?: "",
                                    mediaItems = diary.mediaItems,
                                    weather = diary.weather,
                                    location = diary.location,
                                    moodScore = diary.moodScore,
                                    moodType = diary.moodType,
                                    isHighlight = diary.isHighlight,
                                    isLittleJoy = diary.isLittleJoy,
                                    isLoading = false,
                                    hasUnsavedChanges = false
                                )
                            }
                            loadDiaryInsight(diary.id ?: diaryId)
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "=== 加载日记失败 ===")
                            Log.e(TAG, "错误信息: ${result.message}")
                            
                            _uiState.update { 
                                it.copy(
                                    isLoading = false,
                                    errorMessage = result.message ?: "加载日记失败"
                                )
                            }
                        }
                        is ApiResult.Loading -> {
                            Log.w(TAG, "加载中...")
                            // 保持加载状态
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== 加载日记异常 ===", e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载日记失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun loadDiaryInsight(diaryId: Long) {
        viewModelScope.launch {
            growthPortraitRepository.getDiaryMultimodalInsight(diaryId).collect { result ->
                when (result) {
                    is ApiResult.Success -> _uiState.update { it.copy(multimodalInsight = result.data) }
                    is ApiResult.Error -> _uiState.update { it.copy(multimodalInsight = null) }
                    is ApiResult.Loading -> Unit
                }
            }
        }
    }

    fun refreshMultimodalInsight(diaryId: Long) {
        loadDiaryInsight(diaryId)
    }
    
    /**
     * 加载日记（已废弃，保留用于兼容）
     * 如果日记不存在，显示空白编辑器
     * 
     * 需求：2.1, 2.2
     * 
     * @param userId 用户ID
     * @param date 日记日期
     */
    @Deprecated("Use loadDiaryById for edit mode or showEmptyEditor for create mode")
    private fun loadDiary(userId: Long, date: LocalDate) {
        viewModelScope.launch {
            // 先显示空白编辑器，让用户可以立即开始编辑
            _uiState.update { 
                it.copy(
                    isLoading = true,
                    diaryId = null,
                    title = "",
                    mediaItems = emptyList()
                )
            }
            
            try {
                diaryRepository.getDiaryByDate(userId, date).collect { result ->
                    when (result) {
                        is ApiResult.Success -> {
                            val diary = result.data
                            localCache = diary
                            
                            _uiState.update { 
                                it.copy(
                                    diaryId = diary.id,
                                    title = diary.title ?: "",
                                    mediaItems = diary.mediaItems,
                                    weather = diary.weather,
                                    location = diary.location,
                                    moodScore = diary.moodScore,
                                    moodType = diary.moodType,
                                    isHighlight = diary.isHighlight,
                                    isLittleJoy = diary.isLittleJoy,
                                    isLoading = false,
                                    hasUnsavedChanges = false
                                )
                            }
                        }
                        is ApiResult.Error -> {
                            // 日记不存在或加载失败，显示空白编辑器（已经设置好了）
                            _uiState.update { 
                                it.copy(
                                    isLoading = false,
                                    hasUnsavedChanges = false
                                )
                            }
                        }
                        is ApiResult.Loading -> {
                            // 保持加载状态，但不阻塞UI
                        }
                    }
                }
            } catch (e: Exception) {
                // 网络异常时，直接显示空白编辑器
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        hasUnsavedChanges = false
                    )
                }
            }
        }
    }
    
    /**
     * 更新标题
     * 实时保存到本地缓存
     * 
     * 需求：2.3
     * 
     * @param title 新标题
     */
    fun updateTitle(title: String) {
        _uiState.update { 
            it.copy(
                title = title,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新天气
     * 
     * @param weather 天气信息
     */
    fun updateWeather(weather: String?) {
        _uiState.update { 
            it.copy(
                weather = weather,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新位置
     * 
     * @param location 位置信息
     */
    fun updateLocation(location: String?) {
        _uiState.update { 
            it.copy(
                location = location,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新心情评分
     * 
     * @param score 心情评分 (1-100)
     */
    fun updateMoodScore(score: Int?) {
        _uiState.update { 
            it.copy(
                moodScore = score,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新心情类型
     * 
     * @param type 心情类型
     */
    fun updateMoodType(type: String?) {
        _uiState.update { 
            it.copy(
                moodType = type,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 添加媒体项
     * 自动计算sort_order（基于当前最大值+1）
     * 
     * 需求：2.6, 2.7
     * 
     * @param mediaItem 媒体项
     */
    fun addMediaItem(mediaItem: MediaItem) {
        val currentItems = _uiState.value.mediaItems
        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
        
        // 创建新的媒体项，设置正确的order
        val newItem = when (mediaItem) {
            is MediaItem.Text -> mediaItem.copy(order = maxOrder + 1)
            is MediaItem.Image -> mediaItem.copy(order = maxOrder + 1)
            is MediaItem.Audio -> mediaItem.copy(order = maxOrder + 1)
            is MediaItem.Video -> mediaItem.copy(order = maxOrder + 1)
        }
        
        _uiState.update { 
            it.copy(
                mediaItems = currentItems + newItem,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 删除媒体项
     * 
     * @param mediaItemId 媒体项ID
     */
    fun removeMediaItem(mediaItemId: String) {
        val currentItems = _uiState.value.mediaItems
        val updatedItems = currentItems.filter { it.id != mediaItemId }
        
        _uiState.update { 
            it.copy(
                mediaItems = updatedItems,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新文本媒体项的内容
     * 
     * @param mediaItemId 媒体项ID
     * @param newContent 新的文本内容
     */
    fun updateTextContent(mediaItemId: String, newContent: String) {
        val currentItems = _uiState.value.mediaItems
        val updatedItems = currentItems.map { item ->
            if (item.id == mediaItemId && item is MediaItem.Text) {
                item.copy(content = newContent)
            } else {
                item
            }
        }
        
        _uiState.update { 
            it.copy(
                mediaItems = updatedItems,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 更新媒体项顺序
     * 
     * @param reorderedItems 重新排序后的媒体项列表
     */
    fun reorderMediaItems(reorderedItems: List<MediaItem>) {
        // 重新分配order值
        val itemsWithNewOrder = reorderedItems.mapIndexed { index, item ->
            when (item) {
                is MediaItem.Text -> item.copy(order = index)
                is MediaItem.Image -> item.copy(order = index)
                is MediaItem.Audio -> item.copy(order = index)
                is MediaItem.Video -> item.copy(order = index)
            }
        }
        
        _uiState.update { 
            it.copy(
                mediaItems = itemsWithNewOrder,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 保存日记（两阶段保存）
     * 
     * Phase 1: 保存日记元数据（createDiary/updateDiary）
     * Phase 2: 同步媒体项（syncMediaItems）- 始终调用，即使列表为空（用于删除）
     * Phase 3: 用后端返回的媒体列表更新 UIState（真实 ID 覆盖临时 ID）
     * 
     * 需求：2.4
     */
    fun saveDiary() {
        val state = _uiState.value

        if (state.diaryId == null && state.date.isAfter(LocalDate.now())) {
            _uiState.update {
                it.copy(errorMessage = FUTURE_DIARY_DATE_MESSAGE)
            }
            return
        }
        
        // 验证数据
        if (state.title.isBlank() && state.mediaItems.isEmpty()) {
            _uiState.update { 
                it.copy(
                    errorMessage = "日记内容不能为空"
                )
            }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            
            try {
                // ========== Phase 1: 保存日记元数据 ==========
                val diary = Diary(
                    id = state.diaryId,
                    userId = state.userId,
                    title = state.title.ifBlank { null },
                    date = state.date,
                    mediaItems = emptyList(), // 媒体项由 Phase 2 单独同步
                    weather = state.weather,
                    location = state.location,
                    moodScore = state.moodScore,
                    moodType = state.moodType,
                    isHighlight = state.isHighlight,
                    isLittleJoy = state.isLittleJoy,
                    createdAt = null,
                    updatedAt = null
                )
                
                val metadataFlow = if (state.diaryId == null) {
                    diaryRepository.createDiary(diary)
                } else {
                    diaryRepository.updateDiary(state.diaryId, diary)
                }
                
                // ✅ 必修：只取一次非 Loading 结果，避免 flow 未来多次 emit 导致重复处理
                val phase1 = metadataFlow.first { it !is ApiResult.Loading }
                val savedDiaryId: Long = when (phase1) {
                    is ApiResult.Success -> {
                        val id = phase1.data.id
                        if (id == null) {
                            _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败：未获取到日记ID") }
                            return@launch
                        }
                        Log.d(TAG, "Phase 1 成功: diaryId = $id")
                        id
                    }
                    is ApiResult.Error -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = phase1.message ?: "保存日记元数据失败") }
                        return@launch
                    }
                    else -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = "保存失败：未知状态") }
                        return@launch
                    }
                }
                
                // ✅ 必修：Phase2 开始前重新读取最新媒体项（避免保存中编辑被旧 state 覆盖）
                val latestItems = _uiState.value.mediaItems
                Log.d(TAG, "Phase 2 开始: 同步 ${latestItems.size} 个媒体项")
                
                val syncFlow = diaryRepository.syncMediaItems(savedDiaryId, latestItems)
                
                // ✅ 必修：只取一次非 Loading 结果
                val phase2 = syncFlow.first { it !is ApiResult.Loading }
                val finalMediaItems: List<MediaItem> = when (phase2) {
                    is ApiResult.Success -> {
                        Log.d(TAG, "Phase 2 成功: 同步后 ${phase2.data.size} 个媒体项")
                        phase2.data
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                diaryId = savedDiaryId,
                                isSaving = false,
                                hasUnsavedChanges = true,
                                errorMessage = "日记已保存，但媒体同步失败：${phase2.message ?: "同步媒体项失败"}"
                            )
                        }
                        return@launch
                    }
                    else -> emptyList()
                }
                
                // ========== Phase 3: 更新 UIState（真实 ID 覆盖临时 ID） ==========
                // 再次读取最新状态用于更新 localCache
                val latestState = _uiState.value
                
                localCache = Diary(
                    id = savedDiaryId,
                    userId = latestState.userId,
                    title = latestState.title.ifBlank { null },
                    date = latestState.date,
                    mediaItems = finalMediaItems,
                    weather = latestState.weather,
                    location = latestState.location,
                    moodScore = latestState.moodScore,
                    moodType = latestState.moodType,
                    createdAt = localCache?.createdAt,
                    updatedAt = Instant.now()
                )
                
                _uiState.update { 
                    it.copy(
                        diaryId = savedDiaryId,
                        mediaItems = finalMediaItems, // 用后端返回的真实 ID 覆盖临时 ID
                        isSaving = false,
                        hasUnsavedChanges = false,
                        saveSuccess = true
                    )
                }
                
                Log.d(TAG, "保存完成: diaryId=$savedDiaryId, mediaItems=${finalMediaItems.size}")
                
            } catch (e: Exception) {
                Log.e(TAG, "保存异常", e)
                _uiState.update { 
                    it.copy(
                        isSaving = false,
                        errorMessage = "保存失败: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * 更新本地缓存
     * 实时保存编辑内容到内存缓存
     * 
     * 需求：2.3
     */
    private fun updateLocalCache() {
        val state = _uiState.value
        
        localCache = Diary(
            id = state.diaryId,
            userId = state.userId,
            title = state.title.ifBlank { null },
            date = state.date,
            mediaItems = state.mediaItems,
            weather = state.weather,
            location = state.location,
            moodScore = state.moodScore,
            moodType = state.moodType,
            createdAt = localCache?.createdAt,
            updatedAt = Instant.now()
        )
    }
    
    /**
     * 从本地缓存恢复数据
     * 用于应用重启或配置变更后恢复编辑状态
     * 
     * 需求：2.3
     */
    fun restoreFromCache() {
        localCache?.let { cached ->
            _uiState.update { 
                it.copy(
                    diaryId = cached.id,
                    title = cached.title ?: "",
                    mediaItems = cached.mediaItems,
                    weather = cached.weather,
                    location = cached.location,
                    moodScore = cached.moodScore,
                    moodType = cached.moodType,
                    hasUnsavedChanges = true
                )
            }
        }
    }
    
    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * 重置保存成功状态
     */
    fun resetSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
    
    /**
     * 切换高光时刻状态
     */
    fun toggleHighlight() {
        _uiState.update { 
            it.copy(
                isHighlight = !it.isHighlight,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 切换小确幸状态
     */
    fun toggleLittleJoy() {
        _uiState.update { 
            it.copy(
                isLittleJoy = !it.isLittleJoy,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }
    
    /**
     * 处理语音文件
     * 执行完整的语音处理流程：语音转文字 -> 去除冗余词 -> 情感分析
     * 
     * 需求：8.1, 9.1, 10.1, 11.1
     * 
     * @param audioFile 音频文件
     * @param mediaId 关联的媒体ID
     */
    fun processVoice(audioFile: File, mediaId: Long) {
        val state = _uiState.value
        
        // 验证音频文件
        val validationError = voiceRepository.validateAudioFile(audioFile)
        if (validationError != null) {
            _uiState.update { 
                it.copy(
                    errorMessage = validationError,
                    voiceProcessingState = VoiceProcessingState.Error(validationError)
                )
            }
            return
        }
        
        // 确保有日记ID
        val diaryId = state.diaryId
        if (diaryId == null) {
            _uiState.update { 
                it.copy(
                    errorMessage = "请先保存日记后再处理语音",
                    voiceProcessingState = VoiceProcessingState.Error("请先保存日记")
                )
            }
            return
        }
        
        viewModelScope.launch {
            // 设置处理中状态
            _uiState.update { 
                it.copy(
                    voiceProcessingState = VoiceProcessingState.Processing,
                    errorMessage = null
                )
            }
            
            // 调用语音处理API
            voiceRepository.processVoice(
                audioFile = audioFile,
                diaryId = diaryId,
                mediaId = mediaId
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        val voiceResult = result.data
                        val transcription = voiceResult.toVoiceTranscription()
                        
                        // 更新对应的音频媒体项，添加转录信息
                        updateMediaItemTranscription(mediaId.toString(), transcription)
                        
                        // 将处理后的文本添加为新的文本媒体项
                        addProcessedTextToMediaItems(transcription.processedText)
                        
                        // 更新状态为成功
                        _uiState.update { 
                            it.copy(
                                voiceProcessingState = VoiceProcessingState.Success(transcription),
                                hasUnsavedChanges = true
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update { 
                            it.copy(
                                voiceProcessingState = VoiceProcessingState.Error(
                                    result.message ?: "语音处理失败"
                                ),
                                errorMessage = result.message ?: "语音处理失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { 
                            it.copy(voiceProcessingState = VoiceProcessingState.Processing)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 更新媒体项的转录信息
     * 
     * @param mediaItemId 媒体项ID
     * @param transcription 转录信息
     */
    private fun updateMediaItemTranscription(mediaItemId: String, transcription: VoiceTranscription) {
        val currentItems = _uiState.value.mediaItems
        val updatedItems = currentItems.map { item ->
            if (item.id == mediaItemId && item is MediaItem.Audio) {
                item.copy(transcription = transcription)
            } else {
                item
            }
        }
        
        _uiState.update { 
            it.copy(mediaItems = updatedItems)
        }
        updateLocalCache()
    }
    
    /**
     * 将处理后的文本添加到媒体项列表
     * 
     * @param processedText 处理后的文本
     */
    private fun addProcessedTextToMediaItems(processedText: String) {
        val currentItems = _uiState.value.mediaItems
        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
        
        val textItem = MediaItem.Text(
            id = "text_${System.currentTimeMillis()}",
            content = processedText,
            order = maxOrder + 1,
            createdAt = Instant.now()
        )
        
        _uiState.update { 
            it.copy(
                mediaItems = currentItems + textItem
            )
        }
        updateLocalCache()
    }
    
    /**
     * 清除语音处理状态
     */
    fun clearVoiceProcessingState() {
        _uiState.update { 
            it.copy(voiceProcessingState = VoiceProcessingState.Idle)
        }
    }
    
    /**
     * 带选项的语音处理
     * 根据用户选择的保存模式执行不同的处理流程
     * 
     * 需求: 1.1, 1.2, 1.3, 1.4, 2.4, 5.1, 5.2
     * 
     * @param audioFile 音频文件
     * @param saveAudio 是否保存原语音
     * @param convertToText 是否转换成流畅文字
     */
    fun processVoiceWithOptions(
        audioFile: File,
        saveAudio: Boolean,
        convertToText: Boolean
    ) {
        val state = _uiState.value
        
        // 计算 save_mode
        val saveMode = when {
            saveAudio && convertToText -> 3  // 两者都保存
            convertToText -> 2               // 仅转换成流畅文字
            saveAudio -> 1                   // 仅保存原语音
            else -> {
                _voiceProcessingWithOptionsState.update {
                    it.copy(
                        error = "请至少选择一项保存选项",
                        isProcessing = false
                    )
                }
                return
            }
        }
        
        // 验证音频文件
        val validationError = voiceRepository.validateAudioFile(audioFile)
        if (validationError != null) {
            _voiceProcessingWithOptionsState.update {
                it.copy(
                    error = validationError,
                    isProcessing = false
                )
            }
            return
        }
        
        // 确保有日记ID - 如果没有则先自动保存日记
        val diaryId = state.diaryId
        if (diaryId == null) {
            Log.d(TAG, "日记未保存，先自动保存日记再处理语音")
            
            // 设置处理中状态
            _voiceProcessingWithOptionsState.update {
                it.copy(
                    saveMode = saveMode,
                    isProcessing = true,
                    error = null
                )
            }
            
            // 先保存日记，然后再处理语音
            viewModelScope.launch {
                try {
                    // 创建日记
                    val diary = Diary(
                        id = null,
                        userId = state.userId,
                        title = state.title.ifBlank { "语音日记" },
                        date = state.date,
                        mediaItems = emptyList(),
                        weather = state.weather,
                        location = state.location,
                        moodScore = state.moodScore,
                        moodType = state.moodType,
                        isHighlight = state.isHighlight,
                        isLittleJoy = state.isLittleJoy,
                        createdAt = null,
                        updatedAt = null
                    )
                    
                    val createResult = diaryRepository.createDiary(diary).first { it !is ApiResult.Loading }
                    
                    when (createResult) {
                        is ApiResult.Success -> {
                            val savedDiaryId = createResult.data.id
                            if (savedDiaryId != null) {
                                // 更新 diaryId
                                _uiState.update { it.copy(diaryId = savedDiaryId) }
                                Log.d(TAG, "日记自动保存成功: diaryId=$savedDiaryId")
                                
                                // 继续处理语音
                                processVoiceWithOptionsInternal(audioFile, saveMode, savedDiaryId, state.userId)
                            } else {
                                _voiceProcessingWithOptionsState.update {
                                    it.copy(
                                        error = "自动保存日记失败：未获取到日记ID",
                                        isProcessing = false
                                    )
                                }
                            }
                        }
                        is ApiResult.Error -> {
                            _voiceProcessingWithOptionsState.update {
                                it.copy(
                                    error = "自动保存日记失败: ${createResult.message}",
                                    isProcessing = false
                                )
                            }
                        }
                        else -> {
                            _voiceProcessingWithOptionsState.update {
                                it.copy(
                                    error = "自动保存日记失败：未知错误",
                                    isProcessing = false
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "自动保存日记异常", e)
                    _voiceProcessingWithOptionsState.update {
                        it.copy(
                            error = "自动保存日记失败: ${e.message}",
                            isProcessing = false
                        )
                    }
                }
            }
            return
        }
        
        // 如果已有 diaryId，直接处理语音
        processVoiceWithOptionsInternal(audioFile, saveMode, diaryId, state.userId)
    }
    
    /**
     * 内部方法：执行语音处理的核心逻辑
     */
    private fun processVoiceWithOptionsInternal(
        audioFile: File,
        saveMode: Int,
        diaryId: Long,
        userId: Long
    ) {
        viewModelScope.launch {
            // 设置处理中状态
            _voiceProcessingWithOptionsState.update {
                it.copy(
                    saveMode = saveMode,
                    isProcessing = true,
                    originalText = null,
                    processedText = null,
                    emotion = null,
                    error = null
                )
            }
            
            try {
                // 如果需要保存音频（save_mode=1或3），先上传音频获取 media_id
                var mediaId: Long? = null
                if (saveMode in listOf(1, 3)) {
                    // 上传音频文件
                    val uploadResult = mediaRepository.uploadMediaWithRetrySuspend(
                        file = audioFile,
                        diaryId = diaryId,
                        mediaType = "audio"
                    )
                    
                    when (uploadResult) {
                        is ApiResult.Success -> {
                            mediaId = uploadResult.data.mediaId
                            Log.w(TAG, "===========================================")
                            Log.w(TAG, "✅ 音频上传成功!")
                            Log.w(TAG, "mediaId: $mediaId")
                            Log.w(TAG, "mediaUrl: ${uploadResult.data.mediaUrl}")
                            Log.w(TAG, "===========================================")
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "❌ 音频上传失败: ${uploadResult.message}")
                            _voiceProcessingWithOptionsState.update {
                                it.copy(
                                    error = "音频上传失败: ${uploadResult.message}",
                                    isProcessing = false
                                )
                            }
                            return@launch
                        }
                        else -> {
                            Log.e(TAG, "❌ 音频上传返回未知状态")
                            _voiceProcessingWithOptionsState.update {
                                it.copy(isProcessing = false)
                            }
                            return@launch
                        }
                    }
                }
                
                // 调用带选项的语音处理API
                Log.w(TAG, "===========================================")
                Log.w(TAG, "📞 开始调用语音处理API...")
                Log.w(TAG, "diaryId: $diaryId")
                Log.w(TAG, "userId: $userId")
                Log.w(TAG, "saveMode: $saveMode")
                Log.w(TAG, "mediaId: $mediaId")
                Log.w(TAG, "===========================================")
                
                voiceRepository.processVoiceWithOptions(
                    audioFile = audioFile,
                    diaryId = diaryId,
                    userId = userId,
                    saveMode = saveMode,
                    mediaId = mediaId
                ).collect { result ->
                    when (result) {
                        is ApiResult.Success -> {
                            val response = result.data
                            
                            if (response.success) {
                                // 处理成功
                                val emotionResult = response.emotion?.let { emotion ->
                                    VoiceEmotionResult(
                                        type = emotion.emotionType,
                                        score = emotion.score,
                                        confidence = emotion.confidence
                                    )
                                }
                                
                                // ✅ 添加日志
                                Log.w(TAG, "===========================================")
                                Log.w(TAG, "语音处理API返回成功!")
                                Log.w(TAG, "response.mediaId: ${response.mediaId}")
                                Log.w(TAG, "response.mediaUrl: ${response.mediaUrl}")
                                Log.w(TAG, "response.originalText: ${response.originalText}")
                                Log.w(TAG, "response.processedText: ${response.processedText}")
                                Log.w(TAG, "当前UI中的媒体项数量: ${_uiState.value.mediaItems.size}")
                                Log.w(TAG, "===========================================")
                                
                                _voiceProcessingWithOptionsState.update {
                                    it.copy(
                                        isProcessing = false,
                                        originalText = response.originalText,
                                        processedText = response.processedText,
                                        emotion = emotionResult,
                                        mediaId = response.mediaId,
                                        transcriptionId = response.transcriptionId,
                                        error = null
                                    )
                                }
                                
                                // ✅ 语音处理成功后，重新加载日记的媒体项
                                Log.w(TAG, "===========================================")
                                Log.w(TAG, "准备刷新媒体项列表...")
                                Log.w(TAG, "diaryId: $diaryId")
                                Log.w(TAG, "===========================================")
                                refreshMediaItems(diaryId)
                                
                            } else {
                                // 处理失败
                                Log.e(TAG, "===========================================")
                                Log.e(TAG, "❌ 语音处理API返回失败!")
                                Log.e(TAG, "response.success: ${response.success}")
                                Log.e(TAG, "response.error: ${response.error}")
                                Log.e(TAG, "===========================================")
                                _voiceProcessingWithOptionsState.update {
                                    it.copy(
                                        isProcessing = false,
                                        error = response.error ?: "语音处理失败"
                                    )
                                }
                            }
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "===========================================")
                            Log.e(TAG, "❌ 语音处理API调用失败!")
                            Log.e(TAG, "错误信息: ${result.message}")
                            Log.e(TAG, "===========================================")
                            _voiceProcessingWithOptionsState.update {
                                it.copy(
                                    isProcessing = false,
                                    error = result.message ?: "语音处理失败"
                                )
                            }
                        }
                        is ApiResult.Loading -> {
                            Log.d(TAG, "语音处理API调用中...")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "===========================================")
                Log.e(TAG, "❌ 语音处理流程发生异常!")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常信息: ${e.message}")
                Log.e(TAG, "堆栈跟踪:", e)
                Log.e(TAG, "===========================================")
                _voiceProcessingWithOptionsState.update {
                    it.copy(
                        isProcessing = false,
                        error = "语音处理失败: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * 根据语音处理结果更新媒体项列表
     * 
     * @param saveMode 保存模式
     * @param mediaId 媒体ID
     * @param mediaUrl 音频媒体URL
     * @param originalText 原始文本
     * @param processedText 处理后的文本
     * @param emotion 情绪结果
     * @param audioFile 音频文件
     */
    private fun updateMediaItemsAfterVoiceProcessing(
        saveMode: Int,
        mediaId: Long?,
        mediaUrl: String?,
        originalText: String?,
        processedText: String?,
        emotion: VoiceEmotionResult?,
        audioFile: File
    ) {
        val currentItems = _uiState.value.mediaItems.toMutableList()
        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
        var nextOrder = maxOrder + 1
        
        // 根据 save_mode 添加媒体项
        when (saveMode) {
            1 -> {
                // 仅保存音频
                if (mediaId != null && !mediaUrl.isNullOrBlank()) {
                    val audioItem = MediaItem.Audio(
                        id = "audio_${System.currentTimeMillis()}",
                        assetId = mediaId,
                        url = mediaUrl,  // ✅ 使用服务器返回的 OSS URL
                        duration = 0,
                        transcription = null,
                        order = nextOrder,
                        createdAt = Instant.now()
                    )
                    currentItems.add(audioItem)
                }
            }
            2 -> {
                // 仅转换成流畅文字 - 添加文本媒体项
                if (!processedText.isNullOrBlank()) {
                    val textItem = MediaItem.Text(
                        id = "text_${System.currentTimeMillis()}",
                        assetId = null,
                        content = processedText,
                        order = nextOrder,
                        createdAt = Instant.now()
                    )
                    currentItems.add(textItem)
                }
            }
            3 -> {
                // 两者都保存 - 先添加音频，再添加文本
                if (mediaId != null && !mediaUrl.isNullOrBlank()) {
                    val transcription = if (!originalText.isNullOrBlank() && !processedText.isNullOrBlank()) {
                        VoiceTranscription(
                            originalText = originalText,
                            processedText = processedText,
                            emotion = emotion?.let {
                                EmotionAnalysis(
                                    emotionType = it.type,
                                    score = it.score,
                                    confidence = it.confidence
                                )
                            } ?: EmotionAnalysis(
                                emotionType = "中性",
                                score = 50,
                                confidence = 0f
                            )
                        )
                    } else null
                    
                    val audioItem = MediaItem.Audio(
                        id = "audio_${System.currentTimeMillis()}",
                        assetId = mediaId,
                        url = mediaUrl,  // ✅ 使用服务器返回的 OSS URL
                        duration = 0,
                        transcription = transcription,
                        order = nextOrder,
                        createdAt = Instant.now()
                    )
                    currentItems.add(audioItem)
                    nextOrder++
                }
                
                // 添加文本媒体项
                if (!processedText.isNullOrBlank()) {
                    val textItem = MediaItem.Text(
                        id = "text_${System.currentTimeMillis() + 1}",
                        assetId = null,
                        content = processedText,
                        order = nextOrder,
                        createdAt = Instant.now()
                    )
                    currentItems.add(textItem)
                }
            }
        }
        
        // 更新状态
        _uiState.update {
            it.copy(
                mediaItems = currentItems,
                hasUnsavedChanges = true
            )
        }
        
        // ✅ 添加日志
        Log.d(TAG, "Updated mediaItems after voice processing: count=${currentItems.size}")
        currentItems.forEachIndexed { index, item ->
            when (item) {
                is MediaItem.Audio -> Log.d(TAG, "  [$index] Audio: id=${item.id}, url=${item.url}, assetId=${item.assetId}")
                is MediaItem.Text -> Log.d(TAG, "  [$index] Text: id=${item.id}, content=${item.content.take(50)}")
                else -> Log.d(TAG, "  [$index] Other: $item")
            }
        }
        
        updateLocalCache()
    }
    
    /**
     * 重试语音处理
     * 使用上次的参数重新处理
     * 
     * @param audioFile 音频文件
     */
    fun retryVoiceProcessing(audioFile: File) {
        val currentState = _voiceProcessingWithOptionsState.value
        val saveMode = currentState.saveMode
        
        // 根据 save_mode 反推选项
        val saveAudio = saveMode in listOf(1, 3)
        val convertToText = saveMode in listOf(2, 3)
        
        processVoiceWithOptions(audioFile, saveAudio, convertToText)
    }
    
    /**
     * 清除语音处理（带选项）状态
     */
    fun clearVoiceProcessingWithOptionsState() {
        _voiceProcessingWithOptionsState.update {
            VoiceProcessingWithOptionsUiState()
        }
    }
    
    /**
     * 刷新日记的媒体项列表
     * 用于语音处理完成后更新 UI，不影响用户当前的编辑内容
     * 
     * 修复：添加延迟和重试机制，确保后端数据已更新
     */
    fun refreshMediaItems(diaryId: Long) {
        viewModelScope.launch {
            try {
                Log.w(TAG, "╔═══════════════════════════════════════════════════════════╗")
                Log.w(TAG, "║          开始刷新媒体项列表                                ║")
                Log.w(TAG, "╚═══════════════════════════════════════════════════════════╝")
                Log.w(TAG, "diaryId: $diaryId")
                Log.w(TAG, "当前UI中的媒体项数量: ${_uiState.value.mediaItems.size}")
                
                // 打印当前媒体项
                _uiState.value.mediaItems.forEachIndexed { index, item ->
                    when (item) {
                        is MediaItem.Audio -> Log.w(TAG, "  [$index] Audio: id=${item.id}, url=${item.url}")
                        is MediaItem.Text -> Log.w(TAG, "  [$index] Text: id=${item.id}, content=${item.content.take(30)}...")
                        is MediaItem.Image -> Log.w(TAG, "  [$index] Image: id=${item.id}, url=${item.url}")
                        is MediaItem.Video -> Log.w(TAG, "  [$index] Video: id=${item.id}, url=${item.url}")
                    }
                }
                
                // 添加短暂延迟，确保后端数据已保存
                Log.w(TAG, "等待500ms,确保后端数据已保存...")
                kotlinx.coroutines.delay(500)
                Log.w(TAG, "延迟结束,开始请求后端数据...")
                
                // 使用 first() 而不是 collect，避免阻塞协程
                Log.w(TAG, "调用 diaryRepository.getDiaryById($diaryId)...")
                val result = diaryRepository.getDiaryById(diaryId).first { 
                    it !is ApiResult.Loading 
                }
                Log.w(TAG, "getDiaryById 返回结果: ${result::class.simpleName}")
                
                when (result) {
                    is ApiResult.Success -> {
                        val diary = result.data
                        Log.w(TAG, "╔═══════════════════════════════════════════════════════════╗")
                        Log.w(TAG, "║          刷新成功!                                         ║")
                        Log.w(TAG, "╚═══════════════════════════════════════════════════════════╝")
                        Log.w(TAG, "从后端获取到的媒体项数量: ${diary.mediaItems.size}")
                        
                        // 打印每个媒体项的详细信息
                        diary.mediaItems.forEachIndexed { index, item ->
                            when (item) {
                                is MediaItem.Audio -> {
                                    Log.w(TAG, "  [$index] Audio:")
                                    Log.w(TAG, "       id=${item.id}")
                                    Log.w(TAG, "       assetId=${item.assetId}")
                                    Log.w(TAG, "       url=${item.url}")
                                    Log.w(TAG, "       order=${item.order}")
                                }
                                is MediaItem.Text -> {
                                    Log.w(TAG, "  [$index] Text:")
                                    Log.w(TAG, "       id=${item.id}")
                                    Log.w(TAG, "       content=${item.content.take(50)}...")
                                    Log.w(TAG, "       order=${item.order}")
                                }
                                is MediaItem.Image -> {
                                    Log.w(TAG, "  [$index] Image:")
                                    Log.w(TAG, "       id=${item.id}")
                                    Log.w(TAG, "       url=${item.url}")
                                    Log.w(TAG, "       order=${item.order}")
                                }
                                is MediaItem.Video -> {
                                    Log.w(TAG, "  [$index] Video:")
                                    Log.w(TAG, "       id=${item.id}")
                                    Log.w(TAG, "       url=${item.url}")
                                    Log.w(TAG, "       order=${item.order}")
                                }
                            }
                        }
                        
                        // 只更新 mediaItems，保留用户当前的编辑内容
                        Log.w(TAG, "更新 UI 状态...")
                        _uiState.update { currentState ->
                            currentState.copy(
                                mediaItems = diary.mediaItems,
                                hasUnsavedChanges = true
                            )
                        }
                        
                        Log.w(TAG, "UI 状态更新完成!")
                        Log.w(TAG, "新的UI中的媒体项数量: ${_uiState.value.mediaItems.size}")
                        
                        // 更新本地缓存
                        localCache = diary
                        Log.w(TAG, "╔═══════════════════════════════════════════════════════════╗")
                        Log.w(TAG, "║          刷新完成!                                         ║")
                        Log.w(TAG, "╚═══════════════════════════════════════════════════════════╝")
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "╔═══════════════════════════════════════════════════════════╗")
                        Log.e(TAG, "║          刷新失败!                                         ║")
                        Log.e(TAG, "╚═══════════════════════════════════════════════════════════╝")
                        Log.e(TAG, "错误信息: ${result.message}")
                        Log.e(TAG, "错误码: ${result.code}")
                    }
                    is ApiResult.Loading -> {
                        // 不应该到达这里
                        Log.w(TAG, "警告: first() 返回了 Loading 状态")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "╔═══════════════════════════════════════════════════════════╗")
                Log.e(TAG, "║          刷新异常!                                         ║")
                Log.e(TAG, "╚═══════════════════════════════════════════════════════════╝")
                Log.e(TAG, "异常信息: ${e.message}", e)
            }
        }
    }
    
    /**
     * 显示语音保存选项对话框
     * 需求: 5.1
     */
    fun showVoiceSaveOptionDialog() {
        _voiceProcessingWithOptionsState.update {
            it.copy(showSaveOptionDialog = true)
        }
    }
    
    /**
     * 隐藏语音保存选项对话框
     */
    fun hideVoiceSaveOptionDialog() {
        _voiceProcessingWithOptionsState.update {
            it.copy(showSaveOptionDialog = false)
        }
    }
    
    /**
     * 显示语音处理结果对话框
     * 需求: 5.3
     */
    fun showVoiceProcessResultDialog() {
        _voiceProcessingWithOptionsState.update {
            it.copy(showResultDialog = true)
        }
    }
    
    /**
     * 隐藏语音处理结果对话框
     */
    fun hideVoiceProcessResultDialog() {
        _voiceProcessingWithOptionsState.update {
            it.copy(showResultDialog = false)
        }
    }
    
    /**
     * 上传媒体文件并添加到日记
     * 
     * 流程：
     * 1. 验证文件
     * 2. 如果没有 diaryId，先自动创建日记
     * 3. 上传文件到服务器，获取 assetId (media_id)
     * 4. 创建 MediaItem 并关联 assetId
     * 5. 添加到媒体项列表
     * 
     * 任务 1, 3, 5: 确保 assetId 正确传递和处理
     * 
     * @param file 要上传的文件
     * @param mediaType 媒体类型 (image/audio/video)
     */
    fun uploadAndAddMedia(file: File, mediaType: String) {
        val state = _uiState.value
        
        // 验证文件
        val validationError = mediaRepository.validateFile(file, mediaType)
        if (validationError != null) {
            _uiState.update { 
                it.copy(errorMessage = validationError)
            }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, errorMessage = null) }
            
            try {
                // 如果没有 diaryId，先自动创建日记
                var diaryId = state.diaryId
                if (diaryId == null) {
                    Log.d(TAG, "没有 diaryId，先自动创建日记")
                    val diary = Diary(
                        id = null,
                        userId = state.userId,
                        title = state.title.ifBlank { null },
                        date = state.date,
                        mediaItems = emptyList(),
                        weather = state.weather,
                        location = state.location,
                        moodScore = state.moodScore,
                        moodType = state.moodType,
                        isHighlight = state.isHighlight,
                        isLittleJoy = state.isLittleJoy,
                        createdAt = null,
                        updatedAt = null
                    )
                    
                    val createResult = diaryRepository.createDiary(diary).first { it !is ApiResult.Loading }
                    when (createResult) {
                        is ApiResult.Success -> {
                            diaryId = createResult.data.id
                            if (diaryId == null) {
                                _uiState.update { 
                                    it.copy(isUploading = false, errorMessage = "创建日记失败：未获取到日记ID")
                                }
                                return@launch
                            }
                            Log.d(TAG, "自动创建日记成功: diaryId=$diaryId")
                            _uiState.update { it.copy(diaryId = diaryId) }
                        }
                        is ApiResult.Error -> {
                            _uiState.update { 
                                it.copy(isUploading = false, errorMessage = "创建日记失败: ${createResult.message}")
                            }
                            return@launch
                        }
                        else -> {
                            _uiState.update { it.copy(isUploading = false) }
                            return@launch
                        }
                    }
                }
                
                // 上传文件 - 使用 suspend 版本避免 Flow exception transparency 问题
                val uploadResult = mediaRepository.uploadMediaWithRetrySuspend(
                    file = file,
                    diaryId = diaryId,
                    mediaType = mediaType
                )
                
                when (uploadResult) {
                    is ApiResult.Success -> {
                        val response = uploadResult.data
                        Log.d(TAG, "=====================================")
                        Log.d(TAG, "媒体上传成功!")
                        Log.d(TAG, "  response.mediaId (assetId): ${response.mediaId}")
                        Log.d(TAG, "  response.mediaUrl (原始): ${response.mediaUrl}")
                        Log.d(TAG, "  response.thumbnailUrl (原始): ${response.thumbnailUrl}")
                        Log.d(TAG, "  response.fileSize: ${response.fileSize}")
                        Log.d(TAG, "  response.message: ${response.message}")
                        
                        // ✅ 将相对URL转换为完整URL
                        val fullUrl = com.example.mydiary.data.models.buildFullMediaUrl(response.mediaUrl) ?: ""
                        val fullThumbnailUrl = com.example.mydiary.data.models.buildFullMediaUrl(response.thumbnailUrl)
                        
                        Log.d(TAG, "  fullUrl (转换后): $fullUrl")
                        Log.d(TAG, "  fullThumbnailUrl (转换后): $fullThumbnailUrl")
                        Log.d(TAG, "  URL是否为http(s): ${fullUrl.startsWith("http")}")
                        Log.d(TAG, "=====================================")
                        
                        // 创建 MediaItem 并关联 assetId
                        val currentItems = _uiState.value.mediaItems
                        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
                        
                        val newItem: MediaItem = when (mediaType.lowercase()) {
                            "image" -> MediaItem.Image(
                                id = "image_${System.currentTimeMillis()}",
                                assetId = response.mediaId,  // ✅ 关联 assetId
                                url = fullUrl,               // ✅ 使用完整URL
                                thumbnailUrl = fullThumbnailUrl,
                                order = maxOrder + 1,
                                createdAt = Instant.now()
                            )
                            "audio" -> MediaItem.Audio(
                                id = "audio_${System.currentTimeMillis()}",
                                assetId = response.mediaId,  // ✅ 关联 assetId
                                url = fullUrl,               // ✅ 使用完整URL
                                duration = 0, // 后续可以从文件元数据获取
                                transcription = null,
                                order = maxOrder + 1,
                                createdAt = Instant.now()
                            )
                            "video" -> MediaItem.Video(
                                id = "video_${System.currentTimeMillis()}",
                                assetId = response.mediaId,  // ✅ 关联 assetId
                                url = fullUrl,               // ✅ 使用完整URL
                                thumbnailUrl = fullThumbnailUrl,
                                duration = 0, // 后续可以从文件元数据获取
                                order = maxOrder + 1,
                                createdAt = Instant.now()
                            )
                            else -> {
                                _uiState.update { 
                                    it.copy(
                                        isUploading = false,
                                        errorMessage = "不支持的媒体类型: $mediaType"
                                    )
                                }
                                return@launch
                            }
                        }
                        
                        _uiState.update { 
                            it.copy(
                                mediaItems = currentItems + newItem,
                                isUploading = false,
                                hasUnsavedChanges = true
                            )
                        }
                        updateLocalCache()
                        
                        Log.d(TAG, "媒体项已添加: id=${newItem.id}, assetId=${newItem.assetId}")
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "上传失败: ${uploadResult.message}")
                        // 任务 3: 上传失败时，assetId 保持为 null
                        // 可以选择不添加媒体项，或添加一个没有 assetId 的本地媒体项
                        _uiState.update { 
                            it.copy(
                                isUploading = false,
                                errorMessage = "上传失败: ${uploadResult.message}"
                            )
                        }
                    }
                    else -> {
                        _uiState.update { 
                            it.copy(isUploading = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传异常", e)
                _uiState.update { 
                    it.copy(
                        isUploading = false,
                        errorMessage = "上传失败: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * 添加本地媒体项（不上传，assetId 为 null）
     * 
     * 用于添加本地文件引用，稍后再上传
     * 任务 3: assetId 为 null 时的处理
     * 
     * @param localPath 本地文件路径
     * @param mediaType 媒体类型
     */
    fun addLocalMedia(localPath: String, mediaType: String) {
        val currentItems = _uiState.value.mediaItems
        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
        
        val newItem: MediaItem = when (mediaType.lowercase()) {
            "image" -> MediaItem.Image(
                id = "image_${System.currentTimeMillis()}",
                assetId = null,  // ✅ 本地媒体，assetId 为 null
                url = localPath,
                thumbnailUrl = null,
                order = maxOrder + 1,
                createdAt = Instant.now()
            )
            "audio" -> MediaItem.Audio(
                id = "audio_${System.currentTimeMillis()}",
                assetId = null,  // ✅ 本地媒体，assetId 为 null
                url = localPath,
                duration = 0,
                transcription = null,
                order = maxOrder + 1,
                createdAt = Instant.now()
            )
            "video" -> MediaItem.Video(
                id = "video_${System.currentTimeMillis()}",
                assetId = null,  // ✅ 本地媒体，assetId 为 null
                url = localPath,
                thumbnailUrl = null,
                duration = 0,
                order = maxOrder + 1,
                createdAt = Instant.now()
            )
            else -> return
        }
        
        _uiState.update { 
            it.copy(
                mediaItems = currentItems + newItem,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
    }

    /**
     * 添加从绘画结果页传递的媒体项（图片/视频）
     * 解析 savedDataList 格式：["image:https://...", "video:https://..."]
     *
     * @param savedDataList 包含类型和URL的字符串列表
     * @return 添加的 MediaItem 列表
     */
    fun addDrawingMediaItemsFromSaved(savedDataList: List<String>): List<MediaItem> {
        val currentItems = _uiState.value.mediaItems
        val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1

        val newItems = savedDataList.mapIndexedNotNull { index, data ->
            val parts = data.split(":", limit = 2)
            if (parts.size != 2) return@mapIndexedNotNull null

            val mediaType = parts[0].lowercase()
            val mediaUrl = parts[1]

            when (mediaType) {
                "image" -> MediaItem.Image(
                    id = "drawing_image_${System.currentTimeMillis()}_$index",
                    assetId = null,
                    url = mediaUrl,
                    thumbnailUrl = null,
                    order = maxOrder + 1 + index,
                    createdAt = Instant.now()
                )
                "video" -> MediaItem.Video(
                    id = "drawing_video_${System.currentTimeMillis()}_$index",
                    assetId = null,
                    url = mediaUrl,
                    thumbnailUrl = null,
                    duration = 0,
                    order = maxOrder + 1 + index,
                    createdAt = Instant.now()
                )
                else -> null
            }
        }

        if (newItems.isEmpty()) return emptyList()

        _uiState.update {
            it.copy(
                mediaItems = currentItems + newItems,
                hasUnsavedChanges = true
            )
        }
        updateLocalCache()
        return newItems
    }
}

/**
 * 日记编辑器UI状态
 * 
 * 需求：2.1, 2.2, 2.3, 2.4, 8.1, 9.1, 10.1, 11.1
 */
data class DiaryEditorUiState(
    val diaryId: Long? = null,
    val userId: Long = 0L,  // 初始值，会在 initialize() 中被真实用户 ID 覆盖
    val date: LocalDate = LocalDate.now(),
    val title: String = "",
    val mediaItems: List<MediaItem> = emptyList(),
    val weather: String? = null,
    val location: String? = null,
    val moodScore: Int? = null,
    val moodType: String? = null,
    val isHighlight: Boolean = false,  // 是否高光时刻
    val isLittleJoy: Boolean = false,  // 是否小确幸
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isUploading: Boolean = false,  // 媒体上传中状态
    val hasUnsavedChanges: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null,
    val multimodalInsight: DiaryMultimodalInsightResponse? = null,
    val voiceProcessingState: VoiceProcessingState = VoiceProcessingState.Idle
)

/**
 * 语音处理状态
 * 
 * 需求：8.1, 9.1, 10.1, 11.1
 */
sealed class VoiceProcessingState {
    /**
     * 空闲状态
     */
    object Idle : VoiceProcessingState()
    
    /**
     * 处理中
     */
    object Processing : VoiceProcessingState()
    
    /**
     * 处理成功
     * 
     * @param result 语音转录结果
     */
    data class Success(val result: VoiceTranscription) : VoiceProcessingState()
    
    /**
     * 处理失败
     * 
     * @param message 错误消息
     */
    data class Error(val message: String) : VoiceProcessingState()
}

/**
 * 语音处理（带选项）UI状态
 * 
 * 需求: 1.1, 2.4, 5.1, 5.2
 */
data class VoiceProcessingWithOptionsUiState(
    val saveMode: Int = 0,                    // 保存模式 (1=仅语音, 2=仅文字, 3=两者)
    val isProcessing: Boolean = false,        // 是否正在处理
    val originalText: String? = null,         // 原始转录文本
    val processedText: String? = null,        // 处理后的流畅文本
    val emotion: VoiceEmotionResult? = null,  // 情绪分析结果
    val mediaId: Long? = null,                // 音频媒体ID
    val transcriptionId: Long? = null,        // 转录记录ID
    val error: String? = null,                // 错误信息
    val showSaveOptionDialog: Boolean = false,  // 是否显示保存选项对话框
    val showResultDialog: Boolean = false       // 是否显示处理结果对话框
)

/**
 * 语音情绪分析结果
 * 
 * 需求: 3.2, 3.3
 */
data class VoiceEmotionResult(
    val type: String,       // 情绪类型（积极/消极/中性）
    val score: Int,         // 情绪评分 (1-100)
    val confidence: Float   // 置信度 (0-1)
)
