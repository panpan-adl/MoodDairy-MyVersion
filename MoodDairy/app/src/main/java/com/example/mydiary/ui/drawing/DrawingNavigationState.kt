package com.example.mydiary.ui.drawing

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 绘画流程共享状态管理器
 * 用于在 DrawingResultScreen 保存媒体后，将结果传递给 DiaryEditorScreen
 */
@Singleton
class DrawingNavigationState @Inject constructor() {

    private val _savedMedia = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedMedia: StateFlow<List<SavedMediaItem>> = _savedMedia.asStateFlow()

    private val _diaryIdForSave = MutableStateFlow<Long?>(null)
    val diaryIdForSave: StateFlow<Long?> = _diaryIdForSave.asStateFlow()

    /**
     * 当 SharedDrawingViewModel 完成自动持久化后，
     * 通过此 SharedFlow 通知编辑器（DiaryEditorViewModel）刷新媒体列表。
     * extraBufferCapacity=1 确保非阻塞地发射事件。
     */
    private val _diaryMediaRefreshEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 1
    )
    val diaryMediaRefreshEvents: SharedFlow<Long> = _diaryMediaRefreshEvents.asSharedFlow()

    /**
     * 触发指定 diaryId 的媒体列表刷新事件
     */
    fun emitDiaryMediaRefresh(diaryId: Long) {
        _diaryMediaRefreshEvents.tryEmit(diaryId)
    }

    /**
     * 设置保存到日记的日记ID
     */
    fun setDiaryIdForSave(diaryId: Long?) {
        _diaryIdForSave.value = diaryId
    }

    /**
     * 添加已保存的媒体项
     */
    fun addSavedMedia(type: String, url: String) {
        _savedMedia.value = _savedMedia.value + SavedMediaItem(type, url)
    }

    /**
     * 获取并清除已保存的媒体项
     */
    fun consumeSavedMedia(): List<SavedMediaItem> {
        val items = _savedMedia.value
        _savedMedia.value = emptyList()
        return items
    }

    /**
     * 清除所有状态
     */
    fun clear() {
        _savedMedia.value = emptyList()
        _diaryIdForSave.value = null
    }
}
