package com.example.mydiary.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.emotionCaptureDataStore by preferencesDataStore(name = "emotion_capture_prefs")

/**
 * 表情采集偏好存储（DataStore Preferences）
 *
 * 持久化摄像头表情识别的用户授权（consent）与功能开关（enabled）状态。
 */
@Singleton
class EmotionCapturePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 用户授权状态；null 表示尚未询问过 */
    val consentGranted: Flow<Boolean?> =
        context.emotionCaptureDataStore.data.map { it[KEY_CONSENT_GRANTED] }

    /** 功能开关，默认开启（需先获得授权才会真正采集） */
    val enabled: Flow<Boolean> =
        context.emotionCaptureDataStore.data.map { it[KEY_ENABLED] ?: true }

    suspend fun setConsentGranted(granted: Boolean) {
        context.emotionCaptureDataStore.edit { it[KEY_CONSENT_GRANTED] = granted }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.emotionCaptureDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    companion object {
        private val KEY_CONSENT_GRANTED = booleanPreferencesKey("consent_granted")
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
