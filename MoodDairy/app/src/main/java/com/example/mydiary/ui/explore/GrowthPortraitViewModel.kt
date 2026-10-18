package com.example.mydiary.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.GrowthPortraitStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GrowthPortraitViewModel @Inject constructor(private val store: GrowthPortraitStore) : ViewModel() {
    private val initial = store.cached(30)
    private val _uiState = MutableStateFlow(GrowthPortraitUiState(portrait = initial, isLoading = initial == null))
    val uiState: StateFlow<GrowthPortraitUiState> = _uiState.asStateFlow()
    private var portraitJob: Job? = null

    init { loadPortrait() }

    fun loadPortrait(days: Int = 30) {
        portraitJob?.cancel()
        val snapshot = store.cached(days)
        _uiState.value = GrowthPortraitUiState(portrait = snapshot, selectedDays = days, isLoading = snapshot == null)
        val prepared = store.observe(days)
        fun show(result: ApiResult<GrowthPortraitResponse>) {
            _uiState.update { state -> when (result) {
                is ApiResult.Success -> state.copy(portrait = result.data, isLoading = false, error = null)
                is ApiResult.Error -> state.copy(isLoading = false, error = result.message,
                    portrait = if (result.code == 401 || result.code == 403) null else state.portrait)
                ApiResult.Loading -> state.copy(isLoading = state.portrait == null)
            } }
        }
        show(prepared.value)
        portraitJob = viewModelScope.launch { prepared.collect { show(it) } }
    }
}

data class GrowthPortraitUiState(
    val isLoading: Boolean = false,
    val portrait: GrowthPortraitResponse? = null,
    val selectedDays: Int = 30,
    val error: String? = null,
)
