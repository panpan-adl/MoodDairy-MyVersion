package com.example.mydiary.live2d

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VoiceWakeSettings {
    private val state = MutableStateFlow(true)
    val enabled = state.asStateFlow()
    const val WAITING = "等待唤醒"
    private val statusState = MutableStateFlow(WAITING)
    val status = statusState.asStateFlow()
    fun updateStatus(text: String) { statusState.value = text }
    private var loaded = false
    @Synchronized fun initialize(context: Context) {
        if (loaded) return
        state.value = context.getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean("voice_wake", true)
        loaded = true
    }
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        context.getSharedPreferences("companion", Context.MODE_PRIVATE).edit().putBoolean("voice_wake", value).apply()
        state.value = value
        PetOverlayService.refreshVoiceWake()
    }
}
