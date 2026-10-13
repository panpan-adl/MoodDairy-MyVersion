package com.example.mydiary

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.data.models.TestAnswer
import com.example.mydiary.data.models.TestResult
import com.example.mydiary.data.models.User
import com.example.mydiary.data.repository.TestRepository
import com.example.mydiary.navigation.NavGraph
import com.example.mydiary.navigation.Routes
import com.example.mydiary.ui.theme.MyDiaryTheme
import com.example.mydiary.ui.timer.PomodoroTimerScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class FunctionalUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allBundledQuestionnairesLoadAndScoreOnDevice() = runBlocking {
        val repository = TestRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val index = repository.loadTestIndex().getOrThrow()
        assertEquals(6, index.tests.size)
        for (meta in index.tests) {
            val data = repository.loadTest(meta.testId).getOrThrow()
            assertEquals(data.totalQuestions, data.questions.size)
            for (last in listOf(false, true)) {
                val answers = data.questions.map { q ->
                    val options = q.options
                    if (!options.isNullOrEmpty()) {
                        val choice = if (last) options.last() else options.first()
                        TestAnswer(q.questionId, choice.optionId, choice.scores ?: mapOf("score" to (choice.score ?: 0)))
                    } else {
                        val choices = data.globalOptions.orEmpty()
                        if (choices.isEmpty()) TestAnswer(q.questionId, "")
                        else {
                            val choice = if (last) choices.last() else choices.first()
                            TestAnswer(q.questionId, choice.optionId.toString(), mapOf("score" to choice.score))
                        }
                    }
                }
                val result = repository.calculateResult(data, answers)
                if (result is TestResult.ScoreResult) {
                    assertTrue("${meta.testId} score within range", result.totalScore in 0..result.maxScore)
                    assertTrue(result.description.isNotBlank())
                }
                Log.i("FunctionalQA", "Questionnaire ${meta.testId} scored (${if (last) "last" else "first"} choices)")
            }
        }
    }

    @Test fun timerStartsPausesAndResets() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MyDiaryTheme { PomodoroTimerScreen(onNavigateBack = {}) } }
        }
        compose.onNodeWithText("25:00").assertExists()
        compose.onNodeWithContentDescription("开始").performClick()
        compose.onNodeWithText("专注中...").assertExists()
        compose.onNodeWithContentDescription("暂停").performClick()
        compose.onNodeWithText("已暂停").assertExists()
        compose.onNodeWithContentDescription("重置").performClick()
        compose.onNodeWithText("25:00").assertExists()
        compose.onNodeWithText("点击开始").assertExists()
    }

    @Test fun allMainPagesAndQuestionnaireRoutesMountWithoutCrash() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("qa_token")
        val uid = args.getString("qa_user_id")?.toLongOrNull()
        assumeTrue("Pass dedicated QA account arguments", !token.isNullOrBlank() && uid != null)
        lateinit var nav: NavHostController
        val session = compose.activity.sessionManager
        val originalUser = session.getSession()
        val originalToken = session.getAccessToken()
        try {
            session.saveSession(User(id = uid!!, username = "functional_qa"), token!!)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MyDiaryTheme {
                        nav = rememberNavController()
                        NavGraph(nav, Routes.CALENDAR, session)
                    }
                }
            }
            compose.waitForIdle()
            val routes = listOf(
                Routes.CALENDAR, Routes.CHAT, Routes.EXPLORE, Routes.GROWTH_PORTRAIT,
                Routes.HIGHLIGHTS, Routes.LITTLE_JOYS, Routes.DIARY_SEARCH,
                Routes.PROFILE, Routes.EDIT_PROFILE, Routes.TEST_HUB,
                Routes.ACHIEVEMENT, Routes.SHOP, Routes.POMODORO_TIMER,
                Routes.RECHARGE, Routes.WHITE_NOISE, Routes.drawing(),
                Routes.todoCreate(LocalDate.now()), Routes.diaryEditor(LocalDate.now())
            )
            for (route in routes) {
                compose.runOnIdle { nav.navigate(route) { launchSingleTop = true } }
                compose.waitForIdle()
                compose.onRoot().assertExists()
                compose.runOnIdle { assertNotEquals("Protected page must remain open: $route", Routes.LOGIN, nav.currentDestination?.route) }
                Log.i("FunctionalQA", "Mounted page: $route")
            }
            for (id in listOf("daily_mood_v1", "phq9_v1", "gad7_v1", "mbti_v1", "animal_personality_v1", "holland_v1")) {
                compose.runOnIdle { nav.navigate(Routes.test(id)) }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals("test_start/{test_id}", nav.currentDestination?.route) }
                compose.onRoot().assertExists()
                Log.i("FunctionalQA", "Mounted questionnaire: $id")
            }
            compose.runOnIdle { nav.navigate(Routes.LIVE2D) }
            compose.waitForIdle()
            compose.onRoot().assertExists()
        } finally {
            if (originalUser != null && originalToken != null) session.saveSession(originalUser, originalToken)
            else session.clearSession()
        }
    }
}
