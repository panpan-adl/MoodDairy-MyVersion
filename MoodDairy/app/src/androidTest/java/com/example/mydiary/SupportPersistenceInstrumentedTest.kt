package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import com.example.mydiary.data.local.TestHistoryStorage
import com.example.mydiary.data.models.TestHistoryItem
import com.example.mydiary.whitenoise.WhiteNoiseManager
import com.example.mydiary.whitenoise.WhiteNoiseSoundList
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SupportPersistenceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun testResultHistorySurvivesStorageRecreation() {
        val context = compose.activity
        val prefs = context.getSharedPreferences("test_history", 0)
        val original = prefs.getString("history_items", null)
        try {
            val result = TestHistoryItem("mbti_simplified_v1", "验收用虚构测试", "验收结果", 1L)
            TestHistoryStorage(context, Gson()).append(result)
            val restored = TestHistoryStorage(context, Gson()).load()
            assertEquals(result, restored.first())
        } finally {
            prefs.edit().apply {
                if (original == null) remove("history_items") else putString("history_items", original)
            }.commit()
        }
    }

    @Test fun bundledWhiteNoiseActuallyPlaysAcrossPageChangeAndStops() {
        val manager = WhiteNoiseManager.getInstance()
        val sound = WhiteNoiseSoundList.getAllSounds().first()
        val field = WhiteNoiseManager::class.java.getDeclaredField("players").apply { isAccessible = true }
        val originalVolume = manager.getVolume(sound.id)
        var startedAt = 0L
        try {
            compose.runOnIdle {
                assertFalse("Do not interrupt an existing playback session", manager.isPlaying(sound.id))
                manager.setVolume(sound.id, 0f)
                manager.playSound(compose.activity, sound)
            }
            compose.waitUntil(15000) {
                var ready = false
                compose.runOnIdle {
                    @Suppress("UNCHECKED_CAST")
                    val player = (field.get(manager) as Map<String, ExoPlayer>)[sound.id]
                    ready = player?.isPlaying == true && player.currentPosition > 100
                }
                ready
            }
            compose.runOnIdle {
                @Suppress("UNCHECKED_CAST")
                val player = (field.get(manager) as Map<String, ExoPlayer>).getValue(sound.id)
                startedAt = player.currentPosition
                compose.activity.setContent { Text("切换至其他页面的测试内容") }
            }
            compose.waitUntil(5000) {
                var continued = false
                compose.runOnIdle {
                    @Suppress("UNCHECKED_CAST")
                    val player = (field.get(manager) as Map<String, ExoPlayer>).getValue(sound.id)
                    continued = player.isPlaying && player.currentPosition > startedAt
                }
                continued
            }
            compose.runOnIdle { manager.pauseSound(sound.id); assertFalse(manager.isPlaying(sound.id)) }
        } finally {
            compose.runOnIdle { manager.pauseSound(sound.id); manager.setVolume(sound.id, originalVolume) }
        }
    }
}
