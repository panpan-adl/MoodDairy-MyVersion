package com.example.mydiary.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.toDomain
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 发现页面 ViewModel
 * 管理高光时刻和小确幸日记数据
 */
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val apiService: DiaryApiService,
    private val userRepository: UserRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()
    
    init {
        loadData()
    }
    
    /**
     * 加载高光时刻和小确幸日记
     */
    fun loadData() {
        val userId = userRepository.getCurrentUserId() ?: 1L
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                // 使用 coroutineScope 并行加载高光时刻和小确幸
                coroutineScope {
                    val highlightsDeferred = async {
                        apiService.getHighlightDiaries(userId, limit = 10)
                    }
                    val littleJoysDeferred = async {
                        apiService.getLittleJoyDiaries(userId, limit = 10)
                    }
                    
                    val highlightsResponse = highlightsDeferred.await()
                    val littleJoysResponse = littleJoysDeferred.await()
                    
                    val highlights = if (highlightsResponse.isSuccessful) {
                        highlightsResponse.body()?.map { it.toDomain() } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    
                    val littleJoys = if (littleJoysResponse.isSuccessful) {
                        littleJoysResponse.body()?.map { it.toDomain() } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            highlightDiaries = highlights,
                            littleJoyDiaries = littleJoys,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载失败: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * 刷新数据
     */
    fun refresh() {
        loadData()
    }
}

/**
 * 发现页面 UI 状态
 */
data class ExploreUiState(
    val isLoading: Boolean = false,
    val highlightDiaries: List<Diary> = emptyList(),
    val littleJoyDiaries: List<Diary> = emptyList(),
    val error: String? = null
)
