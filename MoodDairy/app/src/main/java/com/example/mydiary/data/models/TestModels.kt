package com.example.mydiary.data.models

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 测试索引数据
 */
data class TestIndex(
    val version: String,
    @SerializedName("last_updated") val lastUpdated: String,
    @SerializedName("total_tests") val totalTests: Int,
    val categories: List<TestCategory>,
    val tests: List<TestMeta>
)

data class TestCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    val description: String,
    val icon: String
)

data class TestMeta(
    @SerializedName("test_id") val testId: String,
    val name: String,
    val icon: String,
    val category: String,
    @SerializedName("question_count") val questionCount: Int,
    @SerializedName("time_minutes") val timeMinutes: Int,
    val description: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("sort_order") val sortOrder: Int,
    @SerializedName("is_recommended") val isRecommended: Boolean = false,
    @SerializedName("recommended_frequency") val recommendedFrequency: String? = null,
    val badge: String? = null,
    @SerializedName("special_reward") val specialReward: String? = null
)

/**
 * 完整测试数据（通用结构）
 */
data class TestData(
    @SerializedName("test_id") val testId: String,
    @SerializedName("test_name") val testName: String,
    @SerializedName("test_name_en") val testNameEn: String? = null,
    @SerializedName("test_description") val testDescription: String,
    val version: String,
    val category: String,
    val icon: String,
    @SerializedName("total_questions") val totalQuestions: Int,
    @SerializedName("estimated_time_minutes") val estimatedTimeMinutes: Int,
    @SerializedName("result_type") val resultType: String,
    @SerializedName("score_range") val scoreRange: ScoreRange? = null,
    val disclaimer: String? = null,
    val source: String? = null,
    val dimensions: List<TestDimension>? = null,
    val questions: List<TestQuestion>,
    @SerializedName("global_options") val globalOptions: List<GlobalOption>? = null,
    @SerializedName("result_types") val resultTypes: Map<String, TestResultType>? = null,
    // Score tests use arrays here, while Holland uses an object payload.
    @SerializedName("result_interpretation") val resultInterpretation: JsonElement? = null,
    @SerializedName("scoring_rules") val scoringRules: ScoringRules? = null
)

data class TestDimension(
    @SerializedName("dimension_id") val dimensionId: String,
    @SerializedName("dimension_name") val dimensionName: String,
    @SerializedName("pole_a") val poleA: DimensionPole,
    @SerializedName("pole_b") val poleB: DimensionPole
)

data class DimensionPole(
    val code: String,
    val name: String,
    val description: String
)

data class TestQuestion(
    @SerializedName("question_id") val questionId: Int,
    @SerializedName("question_text") val questionText: String,
    @SerializedName("question_type") val questionType: String? = null,
    val dimension: String? = null,
    val options: List<TestOption>? = null,
    @SerializedName("allow_multiple") val allowMultiple: Boolean = false,
    @SerializedName("max_select") val maxSelect: Int? = null,
    val required: Boolean = true,
    val placeholder: String? = null,
    @SerializedName("max_length") val maxLength: Int? = null,
    @SerializedName("is_critical") val isCritical: Boolean = false,
    @SerializedName("critical_threshold") val criticalThreshold: Int? = null,
    @SerializedName("critical_message") val criticalMessage: String? = null
)

data class TestOption(
    @SerializedName("option_id") val optionId: String,
    @SerializedName("option_text") val optionText: String,
    val scores: Map<String, Int>? = null,
    @SerializedName("score") val score: Int? = null
)

data class GlobalOption(
    @SerializedName("option_id") val optionId: Int,
    @SerializedName("option_text") val optionText: String,
    val score: Int
)

data class TestResultType(
    // MBTI类型
    val name: String? = null,
    val emoji: String? = null,
    val brief: String? = null,
    val description: String? = null,
    val strengths: List<String>? = null,
    @SerializedName("growth_areas") val growthAreas: List<String>? = null,
    @SerializedName("compatible_types") val compatibleTypes: List<String>? = null,
    @SerializedName("famous_people") val famousPeople: List<String>? = null,
    
    // 动物人格类型
    val animal: String? = null,
    @SerializedName("animal_en") val animalEn: String? = null,
    val image: String? = null,
    val traits: List<String>? = null,
    @SerializedName("pet_unlocked") val petUnlocked: String? = null
)

data class ScoreInterpretation(
    @SerializedName("score_range") val scoreRange: List<Int>,
    val level: String,
    @SerializedName("level_name") val levelName: String,
    val color: String,
    val description: String,
    val suggestion: String
)

data class ScoreRange(
    val min: Int,
    val max: Int
)

data class ScoringRules(
    val description: String? = null,
    @SerializedName("dimension_calculation") val dimensionCalculation: List<DimensionCalc>? = null
)

data class DimensionCalc(
    val dimension: String,
    val threshold: Int,
    val high: String,
    val low: String
)

/**
 * 用户答题状态
 */
data class TestAnswer(
    val questionId: Int,
    val selectedOptionId: String,
    val scores: Map<String, Int> = emptyMap()
)

/**
 * 测试结果
 */
sealed class TestResult {
    /**
     * MBTI类型结果
     */
    data class MbtiResult(
        val typeCode: String,
        val typeName: String,
        val emoji: String,
        val brief: String,
        val description: String,
        val strengths: List<String>,
        val growthAreas: List<String>,
        val compatibleTypes: List<String>,
        val famousPeople: List<String>,
        val dimensionScores: Map<String, Int>
    ) : TestResult()
    
    /**
     * 动物人格结果
     */
    data class AnimalResult(
        val typeCode: String,
        val animal: String,
        val animalEn: String,
        val emoji: String,
        val image: String,
        val brief: String,
        val description: String,
        val traits: List<String>,
        val petUnlocked: String
    ) : TestResult()
    
    /**
     * 分数型结果（PHQ-9, GAD-7等）
     */
    data class ScoreResult(
        val totalScore: Int,
        val maxScore: Int,
        val level: String,
        val levelName: String,
        val color: String,
        val description: String,
        val suggestion: String,
        val hasCriticalFlag: Boolean = false,
        val criticalMessage: String? = null
    ) : TestResult()
    
    /**
     * 霍兰德职业兴趣结果
     */
    data class HollandResult(
        val typeCode: String,
        val dimensionScores: Map<String, Int>,
        val primaryType: String,
        val description: String,
        val careers: List<String>
    ) : TestResult()
}

/**
 * 测试入口类型（用于UI展示）
 */
enum class TestEntryType(
    val title: String,
    val subtitle: String,
    val testIds: List<String>
) {
    MBTI("MBTI人格", "探索你的16型人格", listOf("mbti_simplified_v1")),
    ANIMAL("动物原型", "发现你的动物灵魂", listOf("animal_personality_v1")),
    EMOTION("情绪图谱", "了解你的情绪状态", listOf("phq9_v1", "gad7_v1")),
    POTENTIAL("潜能挖掘", "探索职业方向", listOf("holland_simplified_v1"))
}

data class TestHistoryItem(
    val testId: String,
    val testName: String,
    val resultSummary: String,
    val timestamp: Long = System.currentTimeMillis(),
)
