package com.example.mydiary

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TuanziInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh() || !node.isVisibleToUser) return null
            val matches = if (text == "然然") {
                node.contentDescription?.toString() == text
            } else {
                node.text?.toString() == text || node.contentDescription?.toString() == text ||
                    node.hintText?.toString() == text
            }
            if (matches) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return automation.windows.firstNotNullOfOrNull { visit(it.root) }
    }

    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { if (it.isVisibleToUser) return it }
            SystemClock.sleep(100)
        }
        error("Visible node not found: $text")
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        check(automation.injectInputEvent(event, true))
        event.recycle()
    }
    private fun click(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        val time = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), time)
        touch(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), time)
    }

    @Test fun catAppearsWithoutOverlayGrantAndSurvivesActivityResume() {
        val info = automation.serviceInfo
        val previousFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        instrumentation.targetContext.getSharedPreferences("companion", 0)
            .edit().putBoolean("enabled", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val before = bounds(await("然然"))
                assertTrue(before.width() > 0)
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, before.exactCenterX(), before.exactCenterY(), down)
                for (step in 1..5) {
                    touch(MotionEvent.ACTION_MOVE, before.exactCenterX() - step * 35f,
                        before.exactCenterY() - step * 12f, down)
                    SystemClock.sleep(25)
                }
                touch(MotionEvent.ACTION_UP, before.exactCenterX() - 175f, before.exactCenterY() - 60f, down)
                SystemClock.sleep(150)
                assertTrue("Cat should move when dragged", bounds(await("然然")).left < before.left - 50)

                click(await("然然 · 打字聊"))
                await("跟然然说点什么…")
                click(await("然然 · 打字聊"))

                click(await("关闭然然"))
                await("开启然然")
                SystemClock.sleep(200)
                assertNull("Hide should remove the companion window", find("然然"))
                click(await("开启然然"))
                await("然然")

                scenario.moveToState(Lifecycle.State.CREATED)
                val hiddenBy = SystemClock.uptimeMillis() + 5_000
                while (find("然然") != null && SystemClock.uptimeMillis() < hiddenBy) SystemClock.sleep(100)
                assertNull("Background activity must not leave an overlay", find("然然"))
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("然然")
            }
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
    }
}
