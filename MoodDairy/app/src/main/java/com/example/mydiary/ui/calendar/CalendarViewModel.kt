package com.example.mydiary.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.TodoItem
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.TodoRepository
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()


    fun selectDate(date: LocalDate): Boolean {
        if (date.isAfter(LocalDate.now())) {
            _uiState.update { it.copy(infoMessage = FUTURE_DATE_MESSAGE) }
            return false
        }
        _uiState.update { it.copy(selectedDate = date) }
        loadDiariesForDate(date)
        loadTodosForDate(date)
        return true
    }

    fun showSavedDiary(date: LocalDate) {
        _uiState.update { it.copy(currentMonth = YearMonth.from(date), selectedDate = date) }
        refreshRecords()
    }

    fun previousMonth() {
        cancelDateLoads()
        _uiState.update {
            it.copy(
                currentMonth = it.currentMonth.minusMonths(1),
                monthAnimationDirection = -1,
                selectedDate = null,
                selectedDateDiaries = emptyList(),
                selectedDateTodos = emptyList()
            )
        }
        loadDatesWithDiary()
    }

    fun nextMonth() {
        cancelDateLoads()
        _uiState.update {
            it.copy(
                currentMonth = it.currentMonth.plusMonths(1),
                monthAnimationDirection = 1,
                selectedDate = null,
                selectedDateDiaries = emptyList(),
                selectedDateTodos = emptyList()
            )
        }
        loadDatesWithDiary()
    }

    fun deleteDiary(diaryId: Long) {
        val userId = getCurrentUserId() ?: run {
            _uiState.update { it.copy(errorMessage = "未登录，无法删除日记") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }

            diaryRepository.deleteDiary(diaryId, userId).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update { state ->
                            state.copy(
                                isDeleting = false,
                                deleteSuccess = true,
                                infoMessage = "日记已删除"
                            )
                        }
                        reloadSelectedDateData()
                        loadDatesWithDiary()
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                errorMessage = result.message ?: "删除日记失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isDeleting = true) }
                    }
                }
            }
        }
    }

    fun toggleTodoDone(todo: TodoItem) {
        if (_uiState.value.isUpdatingTodo) return
        val userId = getCurrentUserId() ?: run {
            _uiState.update { it.copy(errorMessage = "未登录，无法更新待办") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingTodo = true) }

            todoRepository.updateTodo(
                todoId = todo.id,
                userId = userId,
                isDone = !todo.isDone
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update { it.copy(isUpdatingTodo = false) }
                        reloadSelectedDateData()
                        loadDatesWithDiary()
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isUpdatingTodo = false,
                                errorMessage = result.message ?: "更新待办失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isUpdatingTodo = true) }
                    }
                }
            }
        }
    }

    fun deleteTodo(todoId: Long) {
        if (_uiState.value.isUpdatingTodo) return
        val userId = getCurrentUserId() ?: run {
            _uiState.update { it.copy(errorMessage = "未登录，无法删除待办") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingTodo = true) }

            todoRepository.deleteTodo(todoId, userId).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isUpdatingTodo = false,
                                infoMessage = "待办已删除"
                            )
                        }
                        reloadSelectedDateData()
                        loadDatesWithDiary()
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isUpdatingTodo = false,
                                errorMessage = result.message ?: "删除待办失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isUpdatingTodo = true) }
                    }
                }
            }
        }
    }

    fun refreshRecords() {
        loadDatesWithDiary()
        reloadSelectedDateData()
    }

    /** Only replay failed reads; never refresh successful records or replay mutations. */
    fun retryFailedReads() {
        if (retryMonth && monthJob?.isActive != true) loadDatesWithDiary()
        val date = _uiState.value.selectedDate ?: return
        if (retryDiary && diaryJob?.isActive != true) loadDiariesForDate(date)
        if (retryTodo && todoJob?.isActive != true) loadTodosForDate(date)
    }

    fun reloadSelectedDateData() {
        val date = _uiState.value.selectedDate ?: return
        loadDiariesForDate(date)
        loadTodosForDate(date)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun resetDeleteSuccess() {
        _uiState.update { it.copy(deleteSuccess = false) }
    }

    private fun getCurrentUserId(): Long? = userRepository.getCurrentUserId()

    private var monthJob: Job? = null
    private var diaryJob: Job? = null
    private var todoJob: Job? = null
    private var monthRequest = 0
    private var diaryRequest = 0
    private var todoRequest = 0
    private var displayedDiaryDate: LocalDate? = null
    private var retryMonth = false
    private var retryDiary = false
    private var retryTodo = false

    init { loadDatesWithDiary() }

    private fun cancelDateLoads() {
        ++diaryRequest; ++todoRequest
        diaryJob?.cancel(); todoJob?.cancel()
        displayedDiaryDate = null
        retryDiary = false
        retryTodo = false
        _uiState.update { it.copy(isLoadingDiaries = false, isLoadingTodos = false, diaryLoadError = null, todoLoadError = null) }
    }

    private fun loadDatesWithDiary() {
        retryMonth = false
        val request = ++monthRequest
        monthJob?.cancel()
        val userId = getCurrentUserId()
        val month = _uiState.value.currentMonth
        if (userId == null) {
            _uiState.update { it.copy(datesWithDiary = emptySet(), datesWithTodos = emptySet(), isLoading = false, calendarLoadError = null) }
            return
        }
        _uiState.update { it.copy(isLoading = true, calendarLoadError = null) }
        monthJob = viewModelScope.launch {
            val (result, todoResult) = coroutineScope {
                val diaries = async { calendarRead { diaryRepository.getDatesWithDiary(userId, month.year, month.monthValue) } }
                val todos = async { calendarRead { todoRepository.getDatesWithTodos(userId, month.year, month.monthValue) } }
                diaries.await() to todos.await()
            }
            if (request != monthRequest || _uiState.value.currentMonth != month) return@launch
            retryMonth = result.isRetryableReadFailure() || todoResult.isRetryableReadFailure()
            _uiState.update { current ->
                current.copy(
                    datesWithDiary = (result as? ApiResult.Success)?.data?.toSet() ?: current.datesWithDiary,
                    datesWithTodos = (todoResult as? ApiResult.Success)?.data?.toSet() ?: current.datesWithTodos,
                    isLoading = false,
                    calendarLoadError = if (result is ApiResult.Success && todoResult is ApiResult.Success) null
                        else "日历标记暂时没有加载完整，点击重试。"
                )
            }
        }
    }

    private fun loadDiariesForDate(date: LocalDate) {
        retryDiary = false
        val request = ++diaryRequest
        diaryJob?.cancel()
        val userId = getCurrentUserId()
        val keepRecords = displayedDiaryDate == date
        displayedDiaryDate = date
        if (userId == null) {
            _uiState.update { it.copy(selectedDateDiaries = emptyList(), isLoadingDiaries = false, diaryLoadError = null) }
            return
        }
        _uiState.update { it.copy(isLoadingDiaries = true, diaryLoadError = null,
            selectedDateDiaries = if (keepRecords) it.selectedDateDiaries else emptyList()) }
        diaryJob = viewModelScope.launch {
            val result = calendarRead { diaryRepository.getAllDiariesByDate(userId, date) }
            if (request != diaryRequest || _uiState.value.selectedDate != date) return@launch
            retryDiary = result.isRetryableReadFailure()
            _uiState.update { current ->
                when (result) {
                    is ApiResult.Success -> current.copy(selectedDateDiaries = result.data, isLoadingDiaries = false, diaryLoadError = null)
                    else -> current.copy(isLoadingDiaries = false, diaryLoadError = "日记暂时没有加载出来，点击重试；已保存的内容不会被删除。")
                }
            }
        }
    }

    private fun loadTodosForDate(date: LocalDate) {
        retryTodo = false
        val request = ++todoRequest
        todoJob?.cancel()
        val userId = getCurrentUserId()
        if (userId == null) {
            _uiState.update { it.copy(selectedDateTodos = emptyList(), isLoadingTodos = false) }
            return
        }
        _uiState.update { it.copy(isLoadingTodos = true, todoLoadError = null,
            selectedDateTodos = it.selectedDateTodos.filter { todo -> todo.date == date }) }
        todoJob = viewModelScope.launch {
            val result = calendarRead { todoRepository.getTodosByDate(userId, date) }
            if (request != todoRequest || _uiState.value.selectedDate != date) return@launch
            retryTodo = result.isRetryableReadFailure()
            _uiState.update { it.copy(isLoadingTodos = false,
                selectedDateTodos = (result as? ApiResult.Success)?.data ?: it.selectedDateTodos,
                todoLoadError = if (result is ApiResult.Success) null else "待办暂时没有加载出来，点击重试。") }
        }
    }

}

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val monthAnimationDirection: Int = 1,
    val selectedDate: LocalDate? = null,
    val datesWithDiary: Set<LocalDate> = emptySet(),
    val datesWithTodos: Set<LocalDate> = emptySet(),
    val isLoading: Boolean = false,
    val calendarLoadError: String? = null,
    val diaryLoadError: String? = null,
    val todoLoadError: String? = null,
    val selectedDateDiaries: List<Diary> = emptyList(),
    val isLoadingDiaries: Boolean = false,
    val selectedDateTodos: List<TodoItem> = emptyList(),
    val isLoadingTodos: Boolean = false,
    val isDeleting: Boolean = false,
    val isUpdatingTodo: Boolean = false,
    val deleteSuccess: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null
)

internal const val FUTURE_DATE_MESSAGE = "这一天还没到，暂时不能记录。先记下今天的心情吧。"

private fun ApiResult<*>.isRetryableReadFailure(): Boolean =
    this is ApiResult.Error && (code == -1 || code in 500..599)

/** Read-only requests can retry once; a backend outage must never leave the page spinning forever. */
internal suspend fun <T> calendarRead(request: () -> Flow<ApiResult<T>>): ApiResult<T> {
    return withTimeoutOrNull(32_000) {
        var result: ApiResult<T> = ApiResult.Error(-1, "连接暂时不可用")
        repeat(2) { attempt ->
            result = try {
                withTimeoutOrNull(15_000) { request().first { it !is ApiResult.Loading } }
                    ?: ApiResult.Error(-1, "加载超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ApiResult.Error(-1, "加载失败", error)
            }
            val failure = result as? ApiResult.Error
            if (failure == null || (failure.code != -1 && failure.code < 500) || attempt == 1) return@withTimeoutOrNull result
            delay(400)
        }
        result
    } ?: ApiResult.Error(-1, "加载超时")
}
