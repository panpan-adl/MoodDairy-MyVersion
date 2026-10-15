package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.example.mydiary.navigation.Routes
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TodayNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun mount(start: String = Routes.TODAY): NavHostController {
        lateinit var nav: NavHostController
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MyDiaryTheme {
                    nav = rememberNavController()
                    val entry by nav.currentBackStackEntryAsState()
                    Scaffold(bottomBar = { BottomNavBar(nav, entry?.destination?.route) }) { padding ->
                        Box(Modifier.padding(padding)) {
                            NavHost(nav, startDestination = start) {
                                composable(Routes.LOGIN) { Text("登录内容") }
                                composable(Routes.TODAY) { Text("今日内容") }
                                composable(Routes.CHAT) { Text("助手内容") }
                                composable(Routes.CALENDAR) { Text("记录内容") }
                                composable(Routes.EXPLORE) { Text("发现内容") }
                                composable(Routes.PROFILE) { Text("我的内容") }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return nav
    }

    @Test fun todayReopensAfterVisitingAssistantAndRecords() {
        val nav = mount()
        for (label in listOf("助手", "记录", "我的", "发现")) {
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("今天").performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Return from $label", Routes.TODAY, nav.currentDestination?.route) }
            compose.onNodeWithText("今日内容").assertExists()
        }
    }

    @Test fun todayReopensAfterLoginDestinationIsRemoved() {
        val nav = mount(Routes.LOGIN)
        compose.runOnIdle { nav.navigate(Routes.TODAY) { popUpTo(Routes.LOGIN) { inclusive = true } } }
        compose.onNodeWithText("助手").performClick()
        compose.onNodeWithText("今天").performClick()
        compose.runOnIdle { assertEquals(Routes.TODAY, nav.currentDestination?.route) }
    }

    @Test fun todayReturnsFromDirectShortcutsAndRepeatedDestinations() {
        val nav = mount()
        for (route in listOf(Routes.CHAT, Routes.CALENDAR, Routes.CHAT)) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            compose.onNodeWithText("今天").performClick()
            compose.runOnIdle { assertEquals("Return from direct $route", Routes.TODAY, nav.currentDestination?.route) }
        }
        compose.runOnIdle { nav.navigate(Routes.CHAT); nav.navigate(Routes.CHAT) }
        compose.onNodeWithText("今天").performClick()
        compose.runOnIdle { assertEquals("Return from stacked chats", Routes.TODAY, nav.currentDestination?.route) }
    }
    @Test fun todayLabelAndIconRespondToPhysicalTouch() {
        val nav = mount()
        compose.onNodeWithText("助手").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.CHAT, nav.currentDestination?.route) }
        compose.onNodeWithText("今天").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.TODAY, nav.currentDestination?.route) }
        compose.onNodeWithText("记录").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("今天").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.TODAY, nav.currentDestination?.route) }
    }
}
