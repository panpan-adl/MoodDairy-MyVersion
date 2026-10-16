package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.mydiary.ui.life.LifeContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.example.mydiary.navigation.navigateToLife
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

    private fun mount(start: String = Routes.CHAT): NavHostController {
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
                                composable(Routes.LIFE) {
                                    LifeContent(today = { Text("生活内容") }, review = {
                                        TextButton(onClick = { nav.navigate(Routes.GROWTH_PORTRAIT) }) { Text("成长画像入口") }
                                    })
                                }
                                composable(Routes.GROWTH_PORTRAIT) { Text("成长详情") }
                                composable(Routes.TODAY) { LaunchedEffect(Unit) { nav.popBackStack(); nav.navigateToLife("today") } }
                                composable(Routes.CHAT) { Text("助手内容") }
                                composable(Routes.CALENDAR) { Text("记录内容") }
                                composable(Routes.EXPLORE) { LaunchedEffect(Unit) { nav.popBackStack(); nav.navigateToLife("review") } }
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

    @Test fun defaultAssistantAndFourTabsInOrder() {
        val nav = mount()
        compose.runOnIdle { assertEquals(Routes.CHAT, nav.currentDestination?.route) }
        val bounds = listOf("记录", "助手", "生活", "我的").map {
            compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(bounds.sorted(), bounds)
        compose.onNodeWithContentDescription("今天").assertDoesNotExist()
        compose.onNodeWithContentDescription("发现").assertDoesNotExist()
    }

    @Test fun lifeSelectionSurvivesOtherTabsAndDetailReturn() {
        val nav = mount()
        compose.onNodeWithText("生活").performClick()
        compose.onNodeWithText("回顾").performClick()
        compose.onNodeWithText("成长画像入口").performClick()
        compose.onNodeWithText("成长详情").assertExists()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("回顾").assertIsSelected()
        for (tab in listOf("记录", "助手", "我的")) {
            compose.onNodeWithText(tab).performClick()
            compose.onNodeWithText("生活").performClick()
            compose.onNodeWithText("回顾").assertIsSelected()
        }
        compose.onNodeWithText("助手").performClick()
        compose.runOnIdle {
            assertEquals(Routes.CHAT, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    @Test fun legacyEntriesOpenTheRequestedLifeSection() {
        val nav = mount()
        for ((route, section) in listOf(Routes.TODAY to "today", Routes.EXPLORE to "review")) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(Routes.LIFE, nav.currentDestination?.route)
                assertEquals(section, nav.currentBackStackEntry?.savedStateHandle?.get<String>("life_section_request"))
            }
            compose.onNodeWithText("助手").performClick()
        }
    }

    @Test fun todayReopensAfterVisitingAssistantAndRecords() {
        val nav = mount()
        for (label in listOf("助手", "记录", "我的")) {
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("生活").performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Return from $label", Routes.LIFE, nav.currentDestination?.route) }
            compose.onNodeWithText("生活内容").assertExists()
        }
    }

    @Test fun todayReopensAfterLoginDestinationIsRemoved() {
        val nav = mount(Routes.LOGIN)
        compose.runOnIdle { nav.navigate(Routes.CHAT) { popUpTo(Routes.LOGIN) { inclusive = true } } }
        compose.onNodeWithText("助手").performClick()
        compose.onNodeWithText("生活").performClick()
        compose.runOnIdle { assertEquals(Routes.LIFE, nav.currentDestination?.route) }
    }

    @Test fun todayReturnsFromDirectShortcutsAndRepeatedDestinations() {
        val nav = mount()
        for (route in listOf(Routes.CHAT, Routes.CALENDAR, Routes.CHAT)) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            compose.onNodeWithText("生活").performClick()
            compose.runOnIdle { assertEquals("Return from direct $route", Routes.LIFE, nav.currentDestination?.route) }
        }
        compose.runOnIdle { nav.navigate(Routes.CHAT); nav.navigate(Routes.CHAT) }
        compose.onNodeWithText("生活").performClick()
        compose.runOnIdle { assertEquals("Return from stacked chats", Routes.LIFE, nav.currentDestination?.route) }
    }
    @Test fun todayLabelAndIconRespondToPhysicalTouch() {
        val nav = mount()
        compose.onNodeWithText("助手").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.CHAT, nav.currentDestination?.route) }
        compose.onNodeWithText("生活").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.LIFE, nav.currentDestination?.route) }
        compose.onNodeWithText("记录").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("生活").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(Routes.LIFE, nav.currentDestination?.route) }
    }
}
