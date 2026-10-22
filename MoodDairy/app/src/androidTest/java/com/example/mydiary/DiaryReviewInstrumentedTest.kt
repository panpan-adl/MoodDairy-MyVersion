package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.models.DiaryReviewKeyword
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
            compose.waitUntil(30000) { compose.onAllNodesWithText("更新关键词").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("写给未来的自己").assertDoesNotExist()
            compose.onNodeWithText("放进我的空间").assertDoesNotExist()
            compose.onNodeWithText("多模态分析").assertDoesNotExist()
            compose.onNodeWithText("更新关键词").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(140000) { compose.onAllNodesWithText("正在整理词语…").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("这次没能整理出来，你写下的内容还在，可以稍后再试。").assertDoesNotExist()
            compose.onNodeWithText("轻触收起信笺").assertExists()
            // A title draft must not block extraction from confirmed text blocks.
            compose.onNode(hasSetTextAction() and (hasText("标题（可选）") or hasText("测试的记忆")))
                .performTextInput("验收修改的标题")
            compose.onNodeWithText("更新关键词").performScrollTo().performClick()
            compose.onNodeWithText("关键词使用已保存的文字，不必等待整篇日记保存。").assertExists()
            compose.onNodeWithText("先保存这篇日记，再一起回顾吧。").assertDoesNotExist()
        } finally {
            if (original != null && originalToken != null) session.saveSession(original, originalToken)
            else session.clearSession()
        }
    }

    @Test fun cluesAreCollapsedAndNoScoreIsShownByDefault() {
        val review = DiaryReviewResponse(10, "v1", body = "你写下了完成报告后的疲惫，也留下松了一口气的感受。",
            clues = listOf("文字记录：完成了报告", "已有文字分析参考：平静，情绪参考分 60/100（来源：已保存的文字分析）"),
            attachmentNote = "这篇日记还留下了 2 张照片、3 段视频",
            keywords = listOf(DiaryReviewKeyword("疲惫", .8f), DiaryReviewKeyword("松了一口气", .7f)))
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { LazyColumn { item { DiaryReviewCard(review, false, false, null, {}, hasSavedText = true) } } }
        } }
        compose.onNodeWithText("情绪关键词回顾").assertExists()
        compose.onNodeWithText("多模态分析").assertDoesNotExist()
        compose.onNodeWithText("成长关键词", substring = true).assertDoesNotExist()
        compose.onNodeWithText("60/100", substring = true).assertDoesNotExist()
        compose.onNodeWithText("疲惫").assertDoesNotExist()
        compose.onNodeWithText("轻触，打开这篇日记").performClick()
        compose.onNodeWithText("疲惫").assertExists()
        compose.onNodeWithText(review.body!!).assertDoesNotExist()
        compose.onNodeWithText("分析依据").performScrollTo().performClick()
        compose.onNodeWithText(review.body!!).assertExists()
        compose.onNodeWithText("收起依据").performScrollTo().performClick()
        compose.onNodeWithText(review.body!!).assertDoesNotExist()
    }

    @Test fun doubleClickIsDisabledAndExistingReviewSurvivesFailure() {
        var generating by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var calls = 0
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { DiaryReviewCard(DiaryReviewResponse(10, "v1", body = "原来的回顾仍然在这里。"),
                false, generating, error, { calls++; generating = true }, hasSavedText = true) }
        } }
        compose.onNodeWithText("更新关键词").performClick()
        compose.onNodeWithText("更新关键词").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls); generating = false; error = "这次没能整理出来，你写下的内容还在，可以稍后再试。" }
        compose.onNodeWithText("分析依据").performClick()
        compose.onNodeWithText("原来的回顾仍然在这里。").assertExists()
        compose.onNodeWithText(error!!).assertExists()
        compose.onNodeWithText("更新关键词").assertIsEnabled()
    }

    @Test fun unsavedAndMediaOnlyStatesOfferNoInventedEmotion() {
        var dirty by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { it.setContent {
            MyDiaryTheme { DiaryReviewCard(DiaryReviewResponse(10, "v1", hasText = false), dirty, false, null, {}) }
        } }
        compose.onNodeWithText("先点文字卡片的 ✓ 保存文字，就能整理关键词；已有信笺仍可打开。").assertExists()
        compose.onNodeWithText("整理关键词").assertIsNotEnabled()
        compose.runOnIdle { dirty = false }
        compose.onNodeWithText("补充一些文字后，就能留下情绪关键词。").assertExists()
        compose.onNodeWithText("压力", substring = true).assertDoesNotExist()
    }
}
