package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.mydiary.data.models.ChatMessage
import com.example.mydiary.data.models.ChatServiceAction
import com.example.mydiary.data.models.RelaxTrack
import com.example.mydiary.ui.chat.ChatMessageItem
import com.example.mydiary.ui.agent.TodayActions
import com.example.mydiary.ui.agent.TodayDashboard
import com.example.mydiary.ui.agent.TodayState
import com.example.mydiary.ui.relax.RelaxContent
import com.example.mydiary.ui.relax.RelaxMusicState
import com.example.mydiary.ui.theme.MyDiaryTheme
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only: no real messages, paid searches, task writes or playback. */
class SupportFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun restoredTestCardWaitsForUserClickAndKeepsTestId() {
        val action = ChatServiceAction("mbti", "MBTI 人格探索", "点击开始", "open_test",
            JsonObject().apply { addProperty("test_id", "mbti_simplified_v1") })
        val gson = Gson()
        val message = gson.fromJson(gson.toJson(ChatMessage(content = "点击开始测试", isFromUser = false, entryActions = listOf(action))), ChatMessage::class.java)
        var selected: ChatServiceAction? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme { ChatMessageItem(message, onEntryAction = { selected = it }) }
        } }
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithText("MBTI 人格探索").performClick()
        compose.runOnIdle { assertEquals("mbti_simplified_v1", selected?.payload?.get("test_id")?.asString) }
    }

    @Test fun relaxationShowsTwoChoicesWithoutStartingPlayback() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme { RelaxContent(RelaxMusicState(tracks = listOf(RelaxTrack("test", "测试歌曲", "虚构作者", "https://music.163.com/song?id=1"))), {}, {}) }
        } }
        compose.onNodeWithText("放松一下").assertExists()
        compose.onNodeWithText("去网易云播放").assertExists()
        compose.onNodeWithText("白噪音").performClick()
        compose.onNodeWithText("自然声音").assertExists()
        compose.onNodeWithText("音乐").performClick()
        compose.onNodeWithText("测试歌曲").assertExists()
    }

    @Test fun planningKeepsHistoryAccessAndHasNoFocusAction() {
        var requested = false
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MyDiaryTheme { TodayDashboard(TodayState(loaded = true), TodayActions(history = { requested = true })) }
        } }
        compose.onNodeWithText("今日任务").performClick()
        compose.onNodeWithText("查看以往任务").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, requested) }
        compose.onNodeWithText("开始专注").assertDoesNotExist()
    }
}
