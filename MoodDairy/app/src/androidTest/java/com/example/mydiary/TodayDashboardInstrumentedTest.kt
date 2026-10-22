package com.example.mydiary

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.data.models.AgentTask
import com.example.mydiary.navigation.Routes
import com.example.mydiary.ui.agent.TodayActions
import com.example.mydiary.ui.agent.TodayDashboard
import com.example.mydiary.ui.agent.TodayState
import com.example.mydiary.ui.theme.MyDiaryTheme
import com.example.mydiary.ui.life.LifeContent
import androidx.compose.material3.Text
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Uses synthetic in-memory state; never writes account data or calls AI services. */
class TodayDashboardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val initialRecord = "先完成项目提纲，复习英语，再给自己留一点休息时间。"
    private fun mount() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                var state by remember { mutableStateOf(TodayState(loaded = true, record = initialRecord,
                    tasks = listOf(
                        AgentTask(101, "完成项目提纲", "work", 40, "先整理三个核心观点", false, "2026-10-13"),
                        AgentTask(102, "复习英语单词", "study", 20, "复习昨天的词汇，再做五道练习", false, "2026-10-13"),
                        AgentTask(103, "晚饭后散步", "life", 20, "沿熟悉的路线放松走一走", true, "2026-10-13")
                    ))) }
                MyDiaryTheme {
                    val nav = rememberNavController()
                    Scaffold(bottomBar = { BottomNavBar(nav, Routes.LIFE) }) { padding ->
                        Box(Modifier.padding(padding)) {
                            LifeContent(today = { TodayDashboard(state, TodayActions(
                                record = { state = state.copy(record = it) },
                                reflection = { state = state.copy(reflection = it) },
                                toggle = { task -> state = state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(isDone = !it.isDone) else it }) },
                                generateReview = { state = state.copy(review = "今天整理好了项目提纲，也为英语复习留出了时间。明天继续完成方案的细节，照顾好自己的节奏。") }
                            ), showTitle = false) }, review = { Text("回顾内容") })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "today-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun sectionsKeepDraftsAndTaskFiltersSeparate() {
        mount()
        screenshot("record")
        tab("今日任务").performClick()
        tab("今日任务").assertIsSelected()
        compose.onNodeWithText("完成项目提纲").assertExists()
        screenshot("tasks")
        compose.onNode(hasText("工作") and isSelectable()).performClick()
        compose.onNodeWithText("完成项目提纲").assertExists()
        compose.onNodeWithText("复习英语单词").assertDoesNotExist()
        tab("晚间复盘").performClick()
        tab("晚间复盘").assertIsSelected()
        compose.onNodeWithText("回顾今天").assertExists()
        screenshot("review")
        tab("记录与计划").performClick()
        compose.onNodeWithText(initialRecord).assertExists()
        compose.onNodeWithText("回顾今天").assertDoesNotExist()
    }
}
