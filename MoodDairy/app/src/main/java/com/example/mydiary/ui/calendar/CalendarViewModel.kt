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

    init {
        loadDatesWithDiary()
    }

    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
        loadDiariesForDate(date)
        loadTodosForDate(date)
    }

    fun previousMonth() {
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

    private fun loadDatesWithDiary() {
        val userId = getCurrentUserId()
        if (userId == null) {
            _uiState.update {
                it.copy(
                    datesWithDiary = emptySet(),
                    isLoading = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val currentMonth = _uiState.value.currentMonth
            diaryRepository.getDatesWithDiary(
                userId = userId,
                year = currentMonth.year,
                month = currentMonth.monthValue
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                datesWithDiary = result.data.toSet(),
                                isLoading = false
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                datesWithDiary = emptySet(),
                                isLoading = false
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                }
            }
        }
    }

    private fun loadDiariesForDate(date: LocalDate) {
        val userId = getCurrentUserId()
        if (userId == null) {
            _uiState.update {
                it.copy(
                    selectedDateDiaries = emptyList(),
                    isLoadingDiaries = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDiaries = true) }

            diaryRepository.getAllDiariesByDate(userId, date).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                selectedDateDiaries = result.data,
                                isLoadingDiaries = false
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                selectedDateDiaries = emptyList(),
                                isLoadingDiaries = false
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isLoadingDiaries = true) }
                    }
                }
            }
        }
    }

    private fun loadTodosForDate(date: LocalDate) {
        val userId = getCurrentUserId()
        if (userId == null) {
            _uiState.update {
                it.copy(
                    selectedDateTodos = emptyList(),
                    isLoadingTodos = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTodos = true) }

            todoRepository.getTodosByDate(userId, date).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                selectedDateTodos = result.data,
                                isLoadingTodos = false
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                selectedDateTodos = emptyList(),
                                isLoadingTodos = false
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isLoadingTodos = true) }
                    }
                }
            }
        }
    }
}

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val monthAnimationDirection: Int = 1,
    val selectedDate: LocalDate? = null,
    val datesWithDiary: Set<LocalDate> = emptySet(),
    val isLoading: Boolean = false,
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
