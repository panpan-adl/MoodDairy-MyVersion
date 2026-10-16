package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.ui.editor.DiaryReviewCard
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.data.models.User
import com.example.mydiary.ui.editor.DiaryEditorScreen
import java.time.LocalDate

class DiaryReviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedEditorReadsCacheAndBlocksDirtyReview() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("review_qa_token")
        val uid = args.getString("review_qa_user")?.toLongOrNull()
        val diaryId = args.getString("review_qa_diary")?.toLongOrNull()
        assumeTrue("Requires disposable account", token != null && uid != null && diaryId != null)
        val session = compose.activity.sessionManager
        val original = session.getSession()
        val originalToken = session.getAccessToken()
        try {
            session.saveSession(User(id = uid!!, username = "qa_review"), token!!)
            compose.activityRule.scenario.onActivity { it.setContent {
                MyDiaryTheme { DiaryEditorScreen(LocalDate.now(), diaryId, onNavigateBack = {}) }
            } }
            compose.waitUntil(30000) { compose.onAllNodesWithText("重新回顾").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("写给未来的自己").assertDoesNotExist()
            compose.onNodeWithText("放进我的空间").assertDoesNotExist()
            compose.onNodeWithText("多模态分析").assertDoesNotExist()
            compose.onNodeWithText("重新回顾").performScrollTo().performClick()
            compose.waitUntil(140000) { compose.onAllNodesWithText("正在陪你回看…").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("这次没能整理出来，你写下的内容还在，可以稍后再试。").assertDoesNotExist()
            compose.onNodeWithText("重新回顾").assertExists()
            compose.onNodeWithText("标题（可选）").performTextInput("验收修改的标题")
            compose.onNodeWithText("重新回顾").performScrollTo().performClick()
            compose.onNodeWithText("先保存这篇日记，再一起回顾吧。").assertExists()
            compose.onNodeWithText("正在陪你回看…").assertDoesNotExist()
            compose.onNodeWithContentDescription("保存").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("稍后再说").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("稍后再说").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("内容有了变化，这份回顾需要更新。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("验收修改的标题").assertExists()
            compose.onNodeWithText("先保存这篇日记，再一起回顾吧。").assertDoesNotExist()
        } finally {
            if (original != null && originalToken != null) session.saveSession(original, originalToken)
            else session.clearSession()
        }
    }

    @Test fun cluesAreCollapsedAndNoScoreIsShownByDefault() {
        val review = DiaryReviewResponse(10, "v1", body = "你写下了完成报告后的疲惫，也留下松了一口气的感受。",
            clues = listOf("文字记录：完成了报告", "已有文字分析参考：平静，情绪参考分 60/100（来源：已保存的文字分析）"),
            attachmentNote = "这篇日记还留下了 2 张照片、3 段视频")
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { LazyColumn { item { DiaryReviewCard(review, false, false, null, {}) } } }
        } }
        compose.onNodeWithText("这篇日记的小回顾").assertExists()
        compose.onNodeWithText("多模态分析").assertDoesNotExist()
        compose.onNodeWithText("成长关键词", substring = true).assertDoesNotExist()
        compose.onNodeWithText("60/100", substring = true).assertDoesNotExist()
        compose.onNodeWithText("看看记录线索").performScrollTo().performClick()
        compose.onNodeWithText("60/100", substring = true).assertExists()
        compose.onNodeWithText("收起记录线索").performScrollTo().performClick()
        compose.onNodeWithText("60/100", substring = true).assertDoesNotExist()
    }

    @Test fun doubleClickIsDisabledAndExistingReviewSurvivesFailure() {
        var generating by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var calls = 0
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { DiaryReviewCard(DiaryReviewResponse(10, "v1", body = "原来的回顾仍然在这里。"),
                false, generating, error, { calls++; generating = true }) }
        } }
        compose.onNodeWithText("重新回顾").performClick()
        compose.onNodeWithText("正在陪你回看…").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls); generating = false; error = "这次没能整理出来，你写下的内容还在，可以稍后再试。" }
        compose.onNodeWithText("原来的回顾仍然在这里。").assertExists()
        compose.onNodeWithText(error!!).assertExists()
        compose.onNodeWithText("重新回顾").assertIsEnabled()
    }

    @Test fun unsavedAndMediaOnlyStatesOfferNoInventedEmotion() {
        var dirty by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { DiaryReviewCard(DiaryReviewResponse(10, "v1", hasText = false), dirty, false, null, {}) }
        } }
        compose.onNodeWithText("先保存这篇日记，再一起回顾吧。").assertExists()
        compose.runOnIdle { dirty = false }
        compose.onNodeWithText("这些画面也值得留下。愿意的话，可以补一句当时的感受。").assertExists()
        compose.onNodeWithText("压力", substring = true).assertDoesNotExist()
    }
}
