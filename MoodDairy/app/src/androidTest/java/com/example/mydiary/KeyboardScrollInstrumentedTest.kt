package com.example.mydiary

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.data.models.ChatMessage
import com.example.mydiary.data.models.ChatServiceAction
import com.example.mydiary.ui.chat.*
import com.example.mydiary.ui.layout.KeyboardAwareScaffold
import com.example.mydiary.ui.theme.MyDiaryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.io.File

/** All content is synthetic; tests neither send messages nor change the user's session. */
class KeyboardScrollInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var chatState: LazyListState
    private val messages = mutableStateOf(List(45) { ChatMessage("虚构消息 $it " + "用于滚动验证。".repeat(8), it % 2 == 0, timestamp = it.toLong()) })
    private var imeBottom = 0

    private fun mountChat() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme {
                var draft by remember { mutableStateOf("") }
                val density = LocalDensity.current
                imeBottom = WindowInsets.ime.getBottom(density)
                val entries = chatTimelineEntries(messages.value)
                chatState = rememberLazyListState()
                KeyboardAwareScaffold(true, true,
                    topBar = {},
                    bottomBar = { Box(Modifier.fillMaxWidth().height(64.dp)) { Text("底部导航测试") } },
                ) {
                    ChatViewport(chatState, entries,
                        header = { ChatTopBar() },
                        composer = { ChatInputBar(draft, emptyList(), true, false, { draft = it }, {}, {}, {}, {}, {}) },
                    ) {
                        items(entries.asReversed(), key = { it.key }) { ChatMessageItem(it.message) }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun keyboardVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun showKeyboard() {
        compose.onNode(hasSetTextAction()).performClick()
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(10_000) { keyboardVisible() && imeBottom > 0 }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(10_000) { !keyboardVisible() && imeBottom == 0 }
        compose.waitForIdle()
    }

    @After fun closeKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private fun wheel(tag: String, delta: Float, count: Int = 6) {
        // Dialog Compose roots need not start at their decor-window origin.
        val center = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center
        val location = IntArray(2)
        instrumentation.runOnMainSync {
            val window = WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                ?: compose.activity.window.decorView
            window.getLocationOnScreen(location)
        }
        repeat(count) {
            val now = SystemClock.uptimeMillis()
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = center.x + location[0]; y = center.y + location[1]
                setAxisValue(MotionEvent.AXIS_VSCROLL, delta)
            }
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            // Real mouse wheels are preceded by pointer movement. Update the target
            // window too, otherwise Android may retain the previous window's hover.
            val hover = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_HOVER_MOVE }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(hover, true))
            hover.recycle()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            compose.waitForIdle()
        }
    }

    @Test fun keyboardKeepsHeaderFixedAndComposerDockedAcrossRepeatedToggles() {
        mountChat()
        val header = bounds("chat_fixed_header")
        val originalTimeline = bounds("chat_timeline")
        compose.onNode(hasSetTextAction()).performTextInput("只在内存中的测试草稿")
        repeat(2) {
            showKeyboard()
            assertTrue(abs(header.top - bounds("chat_fixed_header").top) < 2f)
            assertTrue(bounds("chat_timeline").height < originalTimeline.height)
            compose.onNodeWithTag("app_bottom_navigation").assertDoesNotExist()
            val composer = bounds("chat_composer")
            val decorHeight = compose.activity.window.decorView.height
            assertTrue("Composer must dock at the IME, with no duplicate inset", abs(composer.bottom - (decorHeight - imeBottom)) < 6f)
            if (it == 0) capture("ime-open.png")
            hideKeyboard()
            compose.onNodeWithTag("app_bottom_navigation").assertExists()
            assertTrue(abs(header.top - bounds("chat_fixed_header").top) < 2f)
            compose.onNode(hasSetTextAction()).assertTextContains("只在内存中的测试草稿")
            if (it == 0) capture("ime-closed.png")
        }
    }

    @Test fun wheelReadsHistoryWithoutReplyOrKeyboardPullingItToLatest() {
        mountChat()
        wheel("chat_timeline", 1f, 12)
        compose.waitUntil(5_000) { chatState.firstVisibleItemIndex > 0 }
        val anchor = compose.runOnIdle { chatState.layoutInfo.visibleItemsInfo.first().key }
        compose.runOnIdle {
            messages.value = messages.value + ChatMessage("新到达的回复", false, timestamp = 100)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(chatState.layoutInfo.visibleItemsInfo.any { it.key == anchor }) }
        showKeyboard()
        compose.runOnIdle { assertTrue(chatState.firstVisibleItemIndex > 0) }
        hideKeyboard()
        wheel("chat_timeline", -1f, 16)
        compose.waitUntil(5_000) { chatState.firstVisibleItemIndex == 0 }
    }

    @Test fun streamingLongReplyAndOwnMessageKeepLatestVisible() {
        mountChat()
        compose.runOnIdle {
            messages.value = messages.value + ChatMessage("回复", false, timestamp = 100, isStreaming = true)
        }
        showKeyboard()
        compose.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(content = "长回复。".repeat(200))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, chatState.firstVisibleItemIndex); assertEquals(0, chatState.firstVisibleItemScrollOffset) }
        wheel("chat_timeline", 1f, 12)
        compose.runOnIdle { messages.value = messages.value + ChatMessage("主动发送的新消息", true, timestamp = 101) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, chatState.firstVisibleItemIndex); assertEquals(0, chatState.firstVisibleItemScrollOffset) }
    }

    @Test fun nativeWheelScrollsRegularPagesAndPrioritizesNestedContent() {
        lateinit var outer: ScrollState
        lateinit var inner: ScrollState
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme {
                outer = rememberScrollState()
                inner = rememberScrollState()
                Column(Modifier.fillMaxSize().testTag("outer").verticalScroll(outer)) {
                    Text("页面顶部")
                    Column(Modifier.fillMaxWidth().height(180.dp).testTag("inner").verticalScroll(inner)) {
                        repeat(80) { Text("内层滚动区域 $it", Modifier.padding(12.dp)) }
                    }
                    repeat(80) { Text("页面记录 $it", Modifier.padding(12.dp)) }
                }
            }
        } }
        wheel("inner", -1f)
        compose.runOnIdle { assertTrue(inner.value > 0); assertEquals(0, outer.value) }
        wheel("inner", 1f, 12)
        compose.runOnIdle { assertEquals(0, inner.value) }
        wheel("outer", -1f)
        compose.runOnIdle { assertTrue(outer.value > 0) }
        wheel("outer", 1f, 12)
        compose.runOnIdle { assertEquals(0, outer.value) }
    }

    private fun capture(name: String) {
        val dir = compose.activity.getExternalFilesDir("keyboard-scroll-qa")!!
        dir.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun wheelScrollsDialogWithoutMovingBackground() {
        lateinit var outer: ScrollState
        lateinit var dialog: ScrollState
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme {
                outer = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(outer)) { repeat(80) { Text("背景 $it") } }
                dialog = rememberScrollState()
                AlertDialog(onDismissRequest = {}, confirmButton = { TextButton(onClick = {}) { Text("确定") } },
                    text = { Column(Modifier.height(240.dp).testTag("dialog_content").verticalScroll(dialog)) {
                        repeat(80) { Text("弹窗记录 $it", Modifier.padding(12.dp)) }
                    } })
            }
        } }
        compose.waitUntil(5_000) {
            WindowInspector.getGlobalWindowViews().any { it !== compose.activity.window.decorView && it.hasWindowFocus() }
        }
        capture("dialog-wheel.png")
        wheel("dialog_content", -1f)
        compose.runOnIdle { assertTrue(dialog.value > 0); assertEquals(0, outer.value) }
        wheel("dialog_content", 1f, 12)
        compose.runOnIdle { assertEquals(0, dialog.value); assertEquals(0, outer.value) }
    }

    @Test fun nonChatFormStillAvoidsKeyboard() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme {
                var draft by remember { mutableStateOf("") }
                imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                KeyboardAwareScaffold(false, false, {}, {}) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        repeat(30) { Text("表单内容 $it", Modifier.padding(8.dp)) }
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("form_field"))
                    }
                }
            }
        } }
        compose.onNodeWithTag("form_field").performScrollTo()
        showKeyboard()
        compose.onNodeWithTag("form_field").assertIsDisplayed()
        assertTrue(bounds("form_field").bottom <= compose.activity.window.decorView.height - imeBottom + 6f)
    }

    @Test fun imageAndEntryCardRemainInScrollableTimelineAboveComposer() {
        messages.value = messages.value + ChatMessage("虚构图片和入口", false, timestamp = 100,
            imageUris = listOf("file:///android_asset/live2d/Diana/raw.preview.png"),
            entryActions = listOf(ChatServiceAction("test", "测试入口", "虚构入口", "open_test")))
        mountChat()
        val header = bounds("chat_fixed_header")
        showKeyboard()
        compose.onNodeWithText("测试入口").assertIsDisplayed()
        assertTrue(abs(header.top - bounds("chat_fixed_header").top) < 2f)
        assertTrue(bounds("chat_timeline").bottom <= bounds("chat_composer").top)
    }
}
