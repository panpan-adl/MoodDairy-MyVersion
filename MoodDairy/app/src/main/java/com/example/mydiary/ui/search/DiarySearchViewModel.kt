package com.example.mydiary.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DiarySearchViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiarySearchUiState())
    val uiState: StateFlow<DiarySearchUiState> = _uiState.asStateFlow()

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun search() {
        val userId = userRepository.getCurrentUserId()
        if (userId == null) {
            _uiState.update { it.copy(errorMessage = "未登录，无法检索日记") }
            return
        }

        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请输入关键字") }
            return
        }

        viewModelScope.launch {
            diaryRepository.searchDiaries(
                userId = userId,
                query = query,
                limit = 50,
                offset = 0
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                results = result.data
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                errorMessage = result.message ?: "检索失败"
                            )
                        }
                    }
                    is ApiResult.Loading -> {
                        _uiState.update { it.copy(isSearching = true) }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

data class DiarySearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Diary> = emptyList(),
    val errorMessage: String? = null
)
