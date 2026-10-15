package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.mydiary.ui.chat.ChatInputBar
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MemberKeyboardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val sent = mutableListOf<String>()
    private fun mount(replyInProgress: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                var draft by remember { mutableStateOf("") }
                MyDiaryTheme {
                    ChatInputBar(inputText = draft, selectedImageUris = emptyList(), enabled = !replyInProgress,
                        isListening = false, onInputChange = { draft = it }, onAddImage = {}, onPickVoiceFile = {},
                        onVoiceInput = {}, onRemoveImage = {}, onSend = { sent.add(draft) })
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun enterDoesNotSendDraftButSendButtonDoes() {
        mount()
        val field = compose.onNode(hasSetTextAction())
        field.performClick().performTextInput("ni")
        field.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
        field.performTextReplacement("你好，今天学习英语")
        compose.onNodeWithContentDescription("send").performClick()
        compose.runOnIdle { assertEquals(listOf("你好，今天学习英语"), sent) }
    }
    @Test fun imeActionDoesNotSendBeforeUserClicksSend() {
        mount()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("测试输入法确认")
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }
    @Test fun draftRemainsEditableDuringReplyButCannotBeSent() {
        mount(replyInProgress = true)
        val field = compose.onNode(hasSetTextAction())
        field.assertIsEnabled().performClick().performTextInput("下一条消息")
        field.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
        field.assertTextContains("下一条消息")
    }
}
