package com.example.mydiary.data.repository

import android.content.Context
import com.example.mydiary.data.models.GlobalOption
import com.example.mydiary.data.models.ScoreInterpretation
import com.example.mydiary.data.models.ScoreRange
import com.example.mydiary.data.models.TestAnswer
import com.example.mydiary.data.models.TestData
import com.example.mydiary.data.models.TestOption
import com.example.mydiary.data.models.TestQuestion
import com.example.mydiary.data.models.TestResult
import com.google.gson.Gson
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestRepositoryScoringTest {

    private val gson = Gson()
    private val repository = TestRepository(mockk<Context>(relaxed = true))

    @Test
    fun `mood score normalizes declared range and ignores text_input`() = runBlocking {
        val data = TestData(
            testId = "daily_mood_v1",
            testName = "Daily Mood",
            testDescription = "desc",
            version = "1.0",
            category = "daily_check",
            icon = "today",
            totalQuestions = 6,
            estimatedTimeMinutes = 1,
            resultType = "mood_score",
            scoreRange = ScoreRange(min = 6, max = 30),
            questions = listOf(
                scaleQuestion(1),
                scaleQuestion(2),
                scaleQuestion(3),
                scaleQuestion(4),
                TestQuestion(
                    questionId = 5,
                    questionText = "q5",
                    questionType = "emotion_select",
                    allowMultiple = true,
                    maxSelect = 3,
                    options = listOf(
                        TestOption(optionId = "happy", optionText = "happy"),
                        TestOption(optionId = "sad", optionText = "sad"),
                    ),
                ),
                TestQuestion(
                    questionId = 6,
                    questionText = "q6",
                    questionType = "text_input",
                ),
            ),
            resultInterpretation = gson.toJsonTree(
                listOf(
                    ScoreInterpretation(
                        scoreRange = listOf(6, 12),
                        level = "low",
                        levelName = "需要关注",
                        color = "#FF9800",
                        description = "low",
                        suggestion = "low",
                    ),
                    ScoreInterpretation(
                        scoreRange = listOf(19, 24),
                        level = "good",
                        levelName = "状态不错",
                        color = "#8BC34A",
                        description = "good",
                        suggestion = "good",
                    ),
                    ScoreInterpretation(
                        scoreRange = listOf(25, 30),
                        level = "excellent",
                        levelName = "状态很好",
                        color = "#4CAF50",
                        description = "excellent",
                        suggestion = "excellent",
                    ),
                ),
            ),
        )

        val answers = listOf(
            TestAnswer(questionId = 1, selectedOptionId = "5"),
            TestAnswer(questionId = 2, selectedOptionId = "4"),
            TestAnswer(questionId = 3, selectedOptionId = "3"),
            TestAnswer(questionId = 4, selectedOptionId = "2"),
            TestAnswer(questionId = 5, selectedOptionId = "happy,sad"),
            TestAnswer(questionId = 6, selectedOptionId = "123"),
        )

        val result = repository.calculateResult(data, answers)
        assertTrue(result is TestResult.ScoreResult)
        val score = result as TestResult.ScoreResult
        assertEquals(21, score.totalScore)
        assertEquals(30, score.maxScore)
        assertEquals("状态不错", score.levelName)
    }

    @Test
    fun `score test uses option score and global option score`() = runBlocking {
        val data = TestData(
            testId = "score_v1",
            testName = "Score",
            testDescription = "desc",
            version = "1.0",
            category = "mental_health",
            icon = "mood",
            totalQuestions = 3,
            estimatedTimeMinutes = 1,
            resultType = "score",
            questions = listOf(
                TestQuestion(
                    questionId = 1,
                    questionText = "q1",
                    options = listOf(
                        TestOption(optionId = "A", optionText = "A", score = 2),
                        TestOption(optionId = "B", optionText = "B", score = 0),
                    ),
                ),
                TestQuestion(
                    questionId = 2,
                    questionText = "q2",
                    options = listOf(
                        TestOption(optionId = "1", optionText = "one"),
                        TestOption(optionId = "2", optionText = "two"),
                    ),
                ),
                TestQuestion(
                    questionId = 3,
                    questionText = "q3",
                    questionType = "text_input",
                ),
            ),
            globalOptions = listOf(
                GlobalOption(optionId = 1, optionText = "one", score = 3),
                GlobalOption(optionId = 2, optionText = "two", score = 1),
            ),
            resultInterpretation = gson.toJsonTree(
                listOf(
                    ScoreInterpretation(
                        scoreRange = listOf(0, 3),
                        level = "low",
                        levelName = "Low",
                        color = "#808080",
                        description = "d",
                        suggestion = "s",
                    ),
                    ScoreInterpretation(
                        scoreRange = listOf(4, 6),
                        level = "high",
                        levelName = "High",
                        color = "#4CAF50",
                        description = "d",
                        suggestion = "s",
                    ),
                ),
            ),
        )

        val answers = listOf(
            TestAnswer(questionId = 1, selectedOptionId = "A"),
            TestAnswer(questionId = 2, selectedOptionId = "1"),
            TestAnswer(questionId = 3, selectedOptionId = "999"),
        )

        val result = repository.calculateResult(data, answers)
        assertTrue(result is TestResult.ScoreResult)
        val score = result as TestResult.ScoreResult
        assertEquals(5, score.totalScore)
        assertEquals(5, score.maxScore)
        assertEquals("High", score.levelName)
    }

    @Test
    fun `multiple selection sums all option scores`() = runBlocking {
        val data = TestData(
            testId = "multi_v1",
            testName = "Multi",
            testDescription = "desc",
            version = "1.0",
            category = "daily_check",
            icon = "today",
            totalQuestions = 1,
            estimatedTimeMinutes = 1,
            resultType = "score",
            questions = listOf(
                TestQuestion(
                    questionId = 1,
                    questionText = "q1",
                    allowMultiple = true,
                    options = listOf(
                        TestOption(optionId = "a", optionText = "a", score = 2),
                        TestOption(optionId = "b", optionText = "b", score = 1),
                        TestOption(optionId = "c", optionText = "c", score = 0),
                    ),
                ),
            ),
            resultInterpretation = gson.toJsonTree(
                listOf(
                    ScoreInterpretation(
                        scoreRange = listOf(0, 1),
                        level = "low",
                        levelName = "Low",
                        color = "#808080",
                        description = "d",
                        suggestion = "s",
                    ),
                    ScoreInterpretation(
                        scoreRange = listOf(2, 3),
                        level = "high",
                        levelName = "High",
                        color = "#4CAF50",
                        description = "d",
                        suggestion = "s",
                    ),
                ),
            ),
        )

        val result = repository.calculateResult(
            data,
            answers = listOf(TestAnswer(questionId = 1, selectedOptionId = "a,b")),
        )

        assertTrue(result is TestResult.ScoreResult)
        assertEquals(3, (result as TestResult.ScoreResult).totalScore)
    }

    private fun scaleQuestion(id: Int): TestQuestion {
        return TestQuestion(
            questionId = id,
            questionText = "q$id",
            options = listOf(
                TestOption(optionId = "1", optionText = "1", score = 1),
                TestOption(optionId = "2", optionText = "2", score = 2),
                TestOption(optionId = "3", optionText = "3", score = 3),
                TestOption(optionId = "4", optionText = "4", score = 4),
                TestOption(optionId = "5", optionText = "5", score = 5),
            ),
        )
    }
}
