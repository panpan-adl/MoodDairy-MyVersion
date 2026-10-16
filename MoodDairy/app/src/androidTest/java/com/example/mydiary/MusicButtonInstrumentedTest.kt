package com.example.mydiary

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.ui.chat.ChatScreen
import com.example.mydiary.ui.chat.ChatViewModel
import com.example.mydiary.ui.chat.EmotionServiceActionUi
import com.example.mydiary.ui.chat.EmotionServiceBundleUi
import com.example.mydiary.ui.theme.MyDiaryTheme
import com.example.mydiary.ui.relax.RelaxContent
import com.example.mydiary.ui.relax.RelaxMusicState
import com.example.mydiary.data.models.RelaxTrack
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MusicButtonInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** A legacy music card offers the unified relaxation page without autoplay. */
    @Test fun musicCardOffersRelaxationBeforeExternalPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    opened.add(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        val actions = CopyOnWriteArrayList<String>()
        try {
            lateinit var viewModel: ChatViewModel
            compose.activityRule.scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]
                activity.setContent {
                    MyDiaryTheme {
                        ChatScreen(userId = activity.sessionManager.getUserId() ?: 1L, viewModel = viewModel,
                            onClientAction = { actions.add(it.action) })
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val field = ChatViewModel::class.java.getDeclaredField("_serviceBundles")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val bundles = field.get(viewModel) as MutableStateFlow<List<EmotionServiceBundleUi>>
                bundles.value = listOf(EmotionServiceBundleUi(
                    bundleId = "music-button-test", title = "情绪关怀", message = "音乐入口测试",
                    triggerReason = "", isCrisis = false,
                    services = listOf(EmotionServiceActionUi(
                        id = "listen_music", title = "听点音乐", description = "", action = "open_music",
                    )),
                ))
            }
            assertTrue(actions.isEmpty())
            compose.onNodeWithText("听点音乐").performClick()
            compose.waitUntil(5000) { actions.isNotEmpty() }
            assertEquals(listOf("open_relax"), actions.toList())
            assertTrue("The card must not start external playback", opened.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun selectedRelaxTrackOpensItsExactSongOnlyAfterClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                MyDiaryTheme { RelaxContent(
                    RelaxMusicState(tracks = listOf(RelaxTrack("qa", "虚构测试曲目", "测试作者", "https://music.163.com/song?id=123"))),
                    onReload = {}, onNavigateBack = {},
                ) }
            } }
            compose.waitForIdle()
            assertTrue(opened.isEmpty())
            compose.onNodeWithText("去网易云播放").performClick()
            assertEquals(1, opened.size)
            assertEquals("https://music.163.com/song?id=123", opened.single().data.toString())
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
