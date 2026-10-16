package com.example.mydiary

import android.view.View
import android.widget.LinearLayout
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.example.mydiary.live2d.PetOverlayService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests display transitions without synthesizing microphone consent or charging requests. */
class AssistantScopeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun service(): PetOverlayService? = PetOverlayService::class.java
        .getDeclaredField("instance").apply { isAccessible = true }.get(null) as? PetOverlayService

    private fun root(): LinearLayout? = service()?.let {
        PetOverlayService::class.java.getDeclaredField("rootView").apply { isAccessible = true }.get(it) as? LinearLayout
    }

    private fun awaitVisible(visible: Boolean, timeout: Long = 5000) {
        compose.waitUntil(timeout) {
            var matches = false
            compose.runOnIdle { matches = (root()?.visibility == View.VISIBLE) == visible }
            matches
        }
    }

    @Test fun assistantOnlyPresenceTransientRecallAndBackgroundCleanup() {
        val original = PetOverlayService.enabled.value
        try {
            compose.onNodeWithText("助手").performClick()
            compose.runOnIdle { PetOverlayService.setEnabled(compose.activity, true) }
            awaitVisible(true)
            compose.onNodeWithText("制作未来视频信").assertDoesNotExist()
            compose.onNodeWithText("布置情绪空间").assertDoesNotExist()
            val originalService = service()
            compose.onNodeWithText("记录").performClick()
            awaitVisible(false)
            assertSame("Changing tabs must keep the listening host alive", originalService, service())
            // Exercise the appearance transition after recognition; this does not assert real mic recognition.
            compose.runOnIdle {
                PetOverlayService::class.java.getDeclaredMethod("revealForInteraction").apply { isAccessible = true }.invoke(service())
            }
            awaitVisible(true)
            Thread.sleep(6000)
            compose.runOnIdle {
                PetOverlayService::class.java.getDeclaredMethod("maybeStartWakeListening").apply { isAccessible = true }.invoke(service())
            }
            // Rearming recognition after silence must not extend the appearance window.
            awaitVisible(false, 7000)
            compose.onNodeWithText("助手").performClick()
            awaitVisible(true)
            val view = root()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(500)
            assertNull(view?.parent)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitVisible(true)
            compose.runOnIdle { PetOverlayService.setEnabled(compose.activity, false) }
            awaitVisible(false)
        } finally {
            compose.runOnIdle { PetOverlayService.setEnabled(compose.activity, original) }
        }
    }

    @Test fun memoryActivityKeepsCompanionHiddenAndAssistantRestoresItOnReturn() {
        val original = PetOverlayService.enabled.value
        val main = compose.activity
        val context = main.applicationContext
        var memory: android.app.Activity? = null
        fun host(): android.app.Activity? = (PetOverlayService::class.java.getDeclaredField("host")
            .apply { isAccessible = true }.get(null) as java.lang.ref.WeakReference<*>).get() as? android.app.Activity
        try {
            compose.onNodeWithText("助手").performClick()
            compose.runOnIdle { PetOverlayService.setEnabled(context, true) }
            awaitVisible(true)
            // Launch exactly like the app. ActivityScenario.launch adds CLEAR_TASK and destroys MainActivity.
            compose.runOnIdle { main.startActivity(Intent(main, com.example.mydiary.memory.MemoryActivity::class.java).putExtra("page", "room")) }
            compose.waitUntil(10000) { service() != null && host() is com.example.mydiary.memory.MemoryActivity && root()?.visibility == View.GONE }
            memory = host()
            compose.runOnIdle { assertSame(memory?.window?.decorView?.rootView, root()?.rootView) }
            compose.runOnIdle { memory?.finish() }
            memory = null
            awaitVisible(true)
        } finally {
            main.runOnUiThread { memory?.finish(); PetOverlayService.setEnabled(context, original) }
        }
    }
}
