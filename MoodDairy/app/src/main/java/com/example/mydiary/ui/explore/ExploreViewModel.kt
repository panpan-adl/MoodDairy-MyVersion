package com.example.mydiary.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.local.ExploreCache
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.toDomain
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.repository.UserRepository
import com.example.mydiary.data.repository.GrowthPortraitStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val apiService: DiaryApiService,
    private val userRepository: UserRepository,
    private val cache: ExploreCache,
    private val portraits: GrowthPortraitStore,
) : ViewModel() {
    private val userId = userRepository.getCurrentUserId() ?: 1L
    private val highlights = cache.load(userId, "highlights")
    private val littleJoys = cache.load(userId, "littleJoys")
    private val _uiState = MutableStateFlow(ExploreUiState(
        highlightDiaries = highlights.orEmpty(),
        littleJoyDiaries = littleJoys.orEmpty(),
        isLoadingHighlights = highlights == null,
        isLoadingLittleJoys = littleJoys == null,
    ))
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()
    // Share the existing portrait request and cache with the detail screen.
    val portrait = portraits.observe(30)
    private var loadJob: Job? = null

    init { loadData() }

    /** Keep snapshots visible and publish each response as soon as it arrives. */
    fun loadData() {
        portraits.prefetch()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            coroutineScope {
                launch {
                    try {
                        val response = apiService.getHighlightDiaries(userId, limit = 10)
                        check(response.isSuccessful) { "高光时刻加载失败 (${response.code()})" }
                        val rows = checkNotNull(response.body())
                        val diaries = rows.map { it.toDomain() }
                        cache.save(userId, "highlights", rows)
                        _uiState.update { it.copy(highlightDiaries = diaries, isLoadingHighlights = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isLoadingHighlights = false, error = e.message) }
                    }
                }
                launch {
                    try {
                        val response = apiService.getLittleJoyDiaries(userId, limit = 10)
                        check(response.isSuccessful) { "小确幸加载失败 (${response.code()})" }
                        val rows = checkNotNull(response.body())
                        val diaries = rows.map { it.toDomain() }
                        cache.save(userId, "littleJoys", rows)
                        _uiState.update { it.copy(littleJoyDiaries = diaries, isLoadingLittleJoys = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isLoadingLittleJoys = false, error = e.message) }
                    }
                }
            }
        }
    }

    fun refresh() = loadData()
}

data class ExploreUiState(
    val highlightDiaries: List<Diary> = emptyList(),
    val littleJoyDiaries: List<Diary> = emptyList(),
    val isLoadingHighlights: Boolean = false,
    val isLoadingLittleJoys: Boolean = false,
    val error: String? = null,
)
