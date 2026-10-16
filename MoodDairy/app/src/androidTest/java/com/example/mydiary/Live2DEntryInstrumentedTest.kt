package com.example.mydiary

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class Live2DEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun assistantOpensBundledDigitalHuman() {
        compose.onNodeWithText("助手").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("数字人").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("数字人").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("数字人 · 然然").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("数字人 · 然然").assertIsDisplayed()
    }
}
