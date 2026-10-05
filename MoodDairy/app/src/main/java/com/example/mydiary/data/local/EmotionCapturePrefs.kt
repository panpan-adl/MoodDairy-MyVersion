package com.example.mydiary.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 情绪采集偏好设置（骨架实现，待补充）
 * 管理用户是否同意开启表情采集
 */
@Singleton
class EmotionCapturePrefs @Inject constructor() {

    private val _consentGranted = MutableStateFlow<Boolean?>(null)
    val consentGranted: StateFlow<Boolean?> = _consentGranted.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setConsentGranted(granted: Boolean) {
        _consentGranted.value = granted
    }
}
