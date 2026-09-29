package com.example.mydiary.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.GrowthPortraitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GrowthPortraitViewModel @Inject constructor(
    private val repository: GrowthPortraitRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GrowthPortraitUiState())
    val uiState: StateFlow<GrowthPortraitUiState> = _uiState.asStateFlow()

    init {
        loadPortrait()
    }

    fun loadPortrait(days: Int = 30) {
        viewModelScope.launch {
            repository.getGrowthPortrait(days = days).collect { result ->
                when (result) {
                    is ApiResult.Loading -> _uiState.update { it.copy(isLoading = true, error = null) }
                    is ApiResult.Success -> _uiState.update {
                        it.copy(
                            isLoading = false,
                            portrait = result.data,
                            selectedDays = days,
                            error = null,
                        )
                    }
                    is ApiResult.Error -> _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }
}

data class GrowthPortraitUiState(
    val isLoading: Boolean = false,
    val portrait: GrowthPortraitResponse? = null,
    val selectedDays: Int = 30,
    val error: String? = null,
)
