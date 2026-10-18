package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.mydiary.ui.life.LifeContent
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.junit.Rule
import org.junit.Test

class LifeScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabSwitchRestoresContentStateAndConsumesShortcutOnce() {
        var request by mutableStateOf<String?>(null)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MyDiaryTheme {
                    LifeContent(request, { request = null }, today = { Text("今日内容") }, review = {
                        var count by rememberSaveable { mutableIntStateOf(0) }
                        TextButton(onClick = { count++ }) { Text("回顾状态 $count") }
                    })
                }
            }
        }
        compose.onNodeWithText("生活").assertDoesNotExist()
        compose.onNodeWithText("今日内容").assertExists()
        compose.onNodeWithText("足迹").performClick()
        compose.onNodeWithText("回顾状态 0").performClick()
        compose.onNodeWithText("当下").performClick()
        compose.onNodeWithText("足迹").performClick()
        compose.onNodeWithText("回顾状态 1").assertExists()
        compose.runOnIdle { request = "today" }
        compose.onNodeWithText("今日内容").assertExists()
        compose.onNodeWithText("足迹").performClick()
        compose.onNodeWithText("回顾状态 1").assertExists()
    }
}
