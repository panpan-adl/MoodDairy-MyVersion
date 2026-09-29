package com.example.mydiary.ui.test

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.local.TestHistoryStorage
import com.example.mydiary.data.models.*
import com.example.mydiary.data.repository.TestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 测试UI状态
 */
data class TestUiState(
    val isLoading: Boolean = true,
    val testData: TestData? = null,
    val testMeta: TestMeta? = null,
    val answers: Map<Int, TestAnswer> = emptyMap(),
    val result: TestResult? = null,
    val error: String? = null
) {
    val answeredCount: Int get() = answers.size
    val totalQuestions: Int get() = testData?.totalQuestions ?: 0
    val progress: Float get() = if (totalQuestions > 0) answeredCount.toFloat() / totalQuestions else 0f
    val isComplete: Boolean
        get() {
            val questions = testData?.questions ?: return false
            return questions
                .filter { it.required }
                .all { question ->
                    answers[question.questionId]?.selectedOptionId?.isNotBlank() == true
                }
        }
}

/**
 * 测试中心UI状态
 */
data class TestHubUiState(
    val isLoading: Boolean = true,
    val testIndex: TestIndex? = null,
    val error: String? = null
)

@HiltViewModel
class TestViewModel @Inject constructor(
    private val testRepository: TestRepository,
    private val testHistoryStorage: TestHistoryStorage,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(TestUiState())
    val uiState: StateFlow<TestUiState> = _uiState.asStateFlow()
    
    private val _hubState = MutableStateFlow(TestHubUiState())
    val hubState: StateFlow<TestHubUiState> = _hubState.asStateFlow()

    private val _history = MutableStateFlow<List<TestHistoryItem>>(emptyList())
    val history: StateFlow<List<TestHistoryItem>> = _history.asStateFlow()
    
    // 从导航参数获取testId
    private val testId: String? = savedStateHandle.get<String>("test_id")
    
    init {
        loadHistory()
        testId?.let { loadTest(it) }
    }

    private fun loadHistory() {
        _history.value = testHistoryStorage.load()
    }
    
    /**
     * 加载测试索引
     */
    fun loadTestIndex() {
        viewModelScope.launch {
            _hubState.update { it.copy(isLoading = true, error = null) }
            
            testRepository.loadTestIndex()
                .onSuccess { index ->
                    _hubState.update { it.copy(isLoading = false, testIndex = index) }
                }
                .onFailure { e ->
                    _hubState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }
    
    /**
     * 加载指定测试
     * @param preserveResult 是否保留现有结果（用于结果页面加载时）
     */
    fun loadTest(testId: String, preserveResult: Boolean = false) {
        viewModelScope.launch {
            // 如果需要保留结果且已有结果，则不重新加载
            if (preserveResult && _uiState.value.result != null) {
                _uiState.update { it.copy(isLoading = false, error = null) }
                return@launch
            }
            
            _uiState.update { it.copy(isLoading = true, error = null, answers = emptyMap(), result = null) }
            
            // 加载测试元信息
            testRepository.getTestMeta(testId)
                .onSuccess { meta ->
                    _uiState.update { it.copy(testMeta = meta) }
                }
            
            // 加载完整测试数据
            testRepository.loadTest(testId)
                .onSuccess { data ->
                    _uiState.update { it.copy(isLoading = false, testData = data) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }
    
    /**
     * 回答问题
     */
    fun answerQuestion(questionId: Int, optionId: String, scores: Map<String, Int> = emptyMap()) {
        _uiState.update { state ->
            if (optionId.isBlank() && scores.isEmpty()) {
                state.copy(answers = state.answers - questionId)
            } else {
                val answer = TestAnswer(
                    questionId = questionId,
                    selectedOptionId = optionId,
                    scores = scores
                )
                state.copy(answers = state.answers + (questionId to answer))
            }
        }
    }
    
    /**
     * 提交测试并计算结果
     */
    fun submitTest() {
        viewModelScope.launch {
            val state = _uiState.value
            val testData = state.testData ?: return@launch
            
            _uiState.update { it.copy(isLoading = true) }
            
            val result = testRepository.calculateResult(testData, state.answers.values.toList())
            
            _uiState.update { it.copy(isLoading = false, result = result) }

            val summary = when (result) {
                is TestResult.ScoreResult -> "${result.totalScore}/${result.maxScore} · ${result.levelName}"
                is TestResult.MbtiResult -> "${result.typeCode} · ${result.typeName}"
                is TestResult.AnimalResult -> "${result.animal} · ${result.brief}"
                is TestResult.HollandResult -> "${result.typeCode} · ${result.primaryType}"
            }

            testHistoryStorage.append(
                TestHistoryItem(
                    testId = testData.testId,
                    testName = testData.testName,
                    resultSummary = summary
                )
            )
            loadHistory()
        }
    }
    
    /**
     * 重置测试
     */
    fun resetTest() {
        _uiState.update { it.copy(answers = emptyMap(), result = null) }
    }
    
    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
        _hubState.update { it.copy(error = null) }
    }
}
