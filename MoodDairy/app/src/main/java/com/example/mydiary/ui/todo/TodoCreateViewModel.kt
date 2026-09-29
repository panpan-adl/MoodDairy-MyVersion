package com.example.mydiary.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.TodoRepository
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TodoCreateViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoCreateUiState())
    val uiState: StateFlow<TodoCreateUiState> = _uiState.asStateFlow()

    fun initialize(date: LocalDate) {
        _uiState.update { state ->
            if (state.date == null) state.copy(date = date) else state
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateNote(note: String) {
        _uiState.update { it.copy(note = note) }
    }

    fun saveTodo() {
        val userId = userRepository.getCurrentUserId()
        if (userId == null) {
            _uiState.update { it.copy(errorMessage = "未登录，无法创建待办") }
            return
        }

        val date = _uiState.value.date
        if (date == null) {
            _uiState.update { it.copy(errorMessage = "日期无效") }
            return
        }

        val title = _uiState.value.title.trim()
        if (title.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "待办标题不能为空") }
            return
        }

        viewModelScope.launch {
            todoRepository.createTodo(
                userId = userId,
                date = date,
                title = title,
                note = _uiState.value.note.takeIf { it.isNotBlank() }
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveSuccess = true
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = result.message ?: "创建待办失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isSaving = true) }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

data class TodoCreateUiState(
    val date: LocalDate? = null,
    val title: String = "",
    val note: String = "",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null
)
