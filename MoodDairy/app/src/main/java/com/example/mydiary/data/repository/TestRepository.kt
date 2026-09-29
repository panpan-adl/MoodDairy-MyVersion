package com.example.mydiary.data.repository

import android.content.Context
import com.example.mydiary.data.models.DimensionCalc
import com.example.mydiary.data.models.ScoreInterpretation
import com.example.mydiary.data.models.TestAnswer
import com.example.mydiary.data.models.TestData
import com.example.mydiary.data.models.TestEntryType
import com.example.mydiary.data.models.TestIndex
import com.example.mydiary.data.models.TestMeta
import com.example.mydiary.data.models.TestQuestion
import com.example.mydiary.data.models.TestResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class TestRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    private var cachedIndex: TestIndex? = null
    private val cachedTests = mutableMapOf<String, TestData>()

    suspend fun loadTestIndex(): Result<TestIndex> = withContext(Dispatchers.IO) {
        try {
            cachedIndex?.let { return@withContext Result.success(it) }

            val jsonString = context.assets.open("tests/index.json").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.removePrefix("\uFEFF")

            val index = gson.fromJson(jsonString, TestIndex::class.java)
            cachedIndex = index
            Result.success(index)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadTest(testId: String): Result<TestData> = withContext(Dispatchers.IO) {
        try {
            cachedTests[testId]?.let { return@withContext Result.success(it) }

            val index = loadTestIndex().getOrThrow()
            val testMeta = index.tests.find { it.testId == testId }
                ?: return@withContext Result.failure(Exception("Test not found: $testId"))

            val jsonString = context.assets.open("tests/${testMeta.filePath}").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.removePrefix("\uFEFF")

            val testData = gson.fromJson(jsonString, TestData::class.java)
            cachedTests[testId] = testData
            Result.success(testData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTestMeta(testId: String): Result<TestMeta> {
        return try {
            val index = loadTestIndex().getOrThrow()
            val meta = index.tests.find { it.testId == testId }
                ?: return Result.failure(Exception("Test not found: $testId"))
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTestsForEntry(entryType: TestEntryType): List<TestMeta> {
        val index = loadTestIndex().getOrNull() ?: return emptyList()
        return index.tests.filter { it.testId in entryType.testIds }
    }

    suspend fun calculateResult(testData: TestData, answers: List<TestAnswer>): TestResult {
        return when (testData.resultType) {
            "mbti_type" -> calculateMbtiResult(testData, answers)
            "animal_type" -> calculateAnimalResult(testData, answers)
            "score" -> calculateScoreResult(testData, answers)
            "mood_score" -> calculateScoreResult(testData, answers, normalizeToDeclaredRange = true)
            "holland_type", "holland_code" -> calculateHollandResult(testData, answers)
            else -> calculateScoreResult(testData, answers)
        }
    }

    private fun calculateMbtiResult(testData: TestData, answers: List<TestAnswer>): TestResult.MbtiResult {
        val dimensionScores = mutableMapOf<String, Int>()

        answers.forEach { answer ->
            answer.scores.forEach { (code, score) ->
                dimensionScores[code] = (dimensionScores[code] ?: 0) + score
            }
        }

        val typeCode = buildString {
            append(if ((dimensionScores["E"] ?: 0) > (dimensionScores["I"] ?: 0)) "E" else "I")
            append(if ((dimensionScores["S"] ?: 0) > (dimensionScores["N"] ?: 0)) "S" else "N")
            append(if ((dimensionScores["T"] ?: 0) > (dimensionScores["F"] ?: 0)) "T" else "F")
            append(if ((dimensionScores["J"] ?: 0) > (dimensionScores["P"] ?: 0)) "J" else "P")
        }

        val resultType = testData.resultTypes?.get(typeCode)

        return TestResult.MbtiResult(
            typeCode = typeCode,
            typeName = resultType?.name ?: typeCode,
            emoji = resultType?.emoji ?: "",
            brief = resultType?.brief ?: "",
            description = resultType?.description ?: "",
            strengths = resultType?.strengths ?: emptyList(),
            growthAreas = resultType?.growthAreas ?: emptyList(),
            compatibleTypes = resultType?.compatibleTypes ?: emptyList(),
            famousPeople = resultType?.famousPeople ?: emptyList(),
            dimensionScores = dimensionScores,
        )
    }

    private fun calculateAnimalResult(testData: TestData, answers: List<TestAnswer>): TestResult.AnimalResult {
        val dimensionScores = mutableMapOf<String, Int>()

        answers.forEach { answer ->
            answer.scores.forEach { (code, score) ->
                dimensionScores[code] = (dimensionScores[code] ?: 0) + score
            }
        }

        val typeCode = buildString {
            testData.scoringRules?.dimensionCalculation?.forEach { calc: DimensionCalc ->
                val highScore = dimensionScores[calc.high] ?: 0
                append(if (highScore >= calc.threshold) calc.high else calc.low)
            }
        }

        val resultType = testData.resultTypes?.get(typeCode)

        return TestResult.AnimalResult(
            typeCode = typeCode,
            animal = resultType?.animal ?: "Unknown",
            animalEn = resultType?.animalEn ?: "Unknown",
            emoji = resultType?.emoji ?: "",
            image = resultType?.image ?: "",
            brief = resultType?.brief ?: "",
            description = resultType?.description ?: "",
            traits = resultType?.traits ?: emptyList(),
            petUnlocked = resultType?.petUnlocked ?: "",
        )
    }

    private fun calculateScoreResult(
        testData: TestData,
        answers: List<TestAnswer>,
        normalizeToDeclaredRange: Boolean = false,
    ): TestResult.ScoreResult {
        val questionById = testData.questions.associateBy { it.questionId }
        val globalScoreByOptionId = testData.globalOptions.orEmpty()
            .associate { it.optionId.toString() to it.score }

        var totalRawScore = 0
        var hasCriticalFlag = false
        var criticalMessage: String? = null

        answers.forEach { answer ->
            val question = questionById[answer.questionId]
            val score = resolveQuestionScore(
                question = question,
                answer = answer,
                globalScoreByOptionId = globalScoreByOptionId,
            )
            totalRawScore += score

            if (question?.isCritical == true && score >= (question.criticalThreshold ?: 1)) {
                hasCriticalFlag = true
                criticalMessage = question.criticalMessage
            }
        }

        val (rawMinScore, rawMaxScore) = calculateRawScoreBounds(
            questions = testData.questions,
            globalScoreByOptionId = globalScoreByOptionId,
        )

        val declaredRange = testData.scoreRange
        val shouldNormalize = normalizeToDeclaredRange &&
            declaredRange != null &&
            rawMaxScore > rawMinScore

        val totalScore = if (shouldNormalize) {
            val normalized = declaredRange!!.min +
                ((totalRawScore - rawMinScore).toFloat() / (rawMaxScore - rawMinScore).toFloat()) *
                (declaredRange.max - declaredRange.min)
            normalized.roundToInt().coerceIn(declaredRange.min, declaredRange.max)
        } else {
            totalRawScore
        }

        val interpretations = parseScoreInterpretations(testData)
            .filter { it.scoreRange.size >= 2 }
            .sortedBy { it.scoreRange[0] }
        val interpretation = interpretations.find { interp ->
            totalScore in interp.scoreRange[0]..interp.scoreRange[1]
        } ?: interpretations.firstOrNull { totalScore < it.scoreRange[0] }
            ?: interpretations.lastOrNull()

        val maxScore = if (shouldNormalize) {
            declaredRange!!.max
        } else if (rawMaxScore > 0) {
            rawMaxScore
        } else {
            (testData.globalOptions?.maxOfOrNull { it.score } ?: 3) * testData.totalQuestions
        }

        return TestResult.ScoreResult(
            totalScore = totalScore,
            maxScore = maxScore,
            level = interpretation?.level ?: "unknown",
            levelName = interpretation?.levelName ?: "未知",
            color = interpretation?.color ?: "#808080",
            description = interpretation?.description ?: "暂无结果解读",
            suggestion = interpretation?.suggestion ?: "暂无建议",
            hasCriticalFlag = hasCriticalFlag,
            criticalMessage = criticalMessage,
        )
    }

    private fun resolveQuestionScore(
        question: TestQuestion?,
        answer: TestAnswer,
        globalScoreByOptionId: Map<String, Int>,
    ): Int {
        if (question == null || question.questionType == "text_input") return 0

        val selectedOptionIds = parseSelectedOptionIds(answer.selectedOptionId)
        if (selectedOptionIds.isEmpty()) return 0

        val optionMap = question.options.orEmpty().associateBy { it.optionId }
        val scoreFromQuestionOptions = selectedOptionIds.sumOf { selectedId ->
            val option = optionMap[selectedId]
            when {
                option?.score != null -> option.score
                option?.scores?.isNotEmpty() == true -> option.scores.values.sum()
                globalScoreByOptionId.containsKey(selectedId) -> globalScoreByOptionId[selectedId]
                else -> selectedId.toIntOrNull()
            } ?: 0
        }

        if (scoreFromQuestionOptions != 0) {
            return scoreFromQuestionOptions
        }

        return selectedOptionIds.sumOf { selectedId ->
            globalScoreByOptionId[selectedId] ?: selectedId.toIntOrNull() ?: 0
        }
    }

    private fun calculateRawScoreBounds(
        questions: List<TestQuestion>,
        globalScoreByOptionId: Map<String, Int>,
    ): Pair<Int, Int> {
        var minScore = 0
        var maxScore = 0

        val globalScores = globalScoreByOptionId.values
        val globalMin = globalScores.minOrNull()
        val globalMax = globalScores.maxOrNull()

        questions.forEach { question ->
            if (question.questionType == "text_input") return@forEach

            val perOptionScores = question.options.orEmpty().mapNotNull { option ->
                option.score
                    ?: option.scores?.values?.sum()
                    ?: globalScoreByOptionId[option.optionId]
                    ?: option.optionId.toIntOrNull()
            }

            val minPerQuestion = when {
                perOptionScores.isNotEmpty() -> perOptionScores.minOrNull()
                globalMin != null -> globalMin
                else -> null
            } ?: 0

            val maxPerQuestion = when {
                perOptionScores.isNotEmpty() -> perOptionScores.maxOrNull()
                globalMax != null -> globalMax
                else -> null
            } ?: 0

            minScore += minPerQuestion
            maxScore += maxPerQuestion
        }

        return minScore to maxScore
    }

    private fun parseSelectedOptionIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseScoreInterpretations(testData: TestData): List<ScoreInterpretation> {
        val interpretation = testData.resultInterpretation ?: return emptyList()
        if (!interpretation.isJsonArray) return emptyList()

        val array: JsonArray = interpretation.asJsonArray
        return array.mapNotNull { element ->
            runCatching { gson.fromJson(element, ScoreInterpretation::class.java) }.getOrNull()
        }
    }

    private fun calculateHollandResult(testData: TestData, answers: List<TestAnswer>): TestResult.HollandResult {
        val dimensionScores = mutableMapOf<String, Int>()

        answers.forEach { answer ->
            answer.scores.forEach { (code, score) ->
                dimensionScores[code] = (dimensionScores[code] ?: 0) + score
            }
        }

        val sortedTypes = dimensionScores.entries.sortedByDescending { it.value }
        val typeCode = sortedTypes.take(3).map { it.key }.joinToString("")
        val primaryType = sortedTypes.firstOrNull()?.key ?: "R"

        val resultType = testData.resultTypes?.get(primaryType)

        return TestResult.HollandResult(
            typeCode = typeCode,
            dimensionScores = dimensionScores,
            primaryType = primaryType,
            description = resultType?.description ?: "",
            careers = emptyList(),
        )
    }
}
