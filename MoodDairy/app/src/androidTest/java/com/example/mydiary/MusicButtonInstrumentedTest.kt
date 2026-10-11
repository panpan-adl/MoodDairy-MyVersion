package com.example.mydiary

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MusicButtonInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Clicks the real chat card and verifies the app-to-browser fallback without leaving the test. */
    @Test fun musicCardButtonOpensNetease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    opened.add(Intent(intent))
                    if (intent.`package` == "com.netease.cloudmusic") throw ActivityNotFoundException()
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            lateinit var viewModel: ChatViewModel
            compose.activityRule.scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]
                activity.setContent {
                    MyDiaryTheme {
                        ChatScreen(userId = activity.sessionManager.getUserId() ?: 1L, viewModel = viewModel)
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
            compose.onNodeWithText("听点音乐").performClick()
            assertTrue("The real button must dispatch an external music intent", opened.isNotEmpty())
            assertEquals("com.netease.cloudmusic", opened.first().`package`)
            assertEquals(2, opened.size)
            assertNull(opened.last().`package`)
            assertEquals("https://music.163.com/", opened.last().data.toString())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}