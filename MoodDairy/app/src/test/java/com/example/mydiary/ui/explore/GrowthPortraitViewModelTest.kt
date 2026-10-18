package com.example.mydiary.ui.explore

import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.GrowthPortraitStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrowthPortraitViewModelTest {
    @Test fun allPreparedPeriodsDisplaySynchronouslyWithoutLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = mockk<GrowthPortraitStore>()
            val portraits = listOf(7, 30, 90).associateWith { mockk<GrowthPortraitResponse>() }
            portraits.forEach { (days, portrait) ->
                every { store.cached(days) } returns portrait
                every { store.observe(days) } returns MutableStateFlow(ApiResult.Success(portrait))
            }
            val model = GrowthPortraitViewModel(store)
            listOf(30, 7, 90, 30).forEach { days ->
                model.loadPortrait(days)
                assertSame(portraits[days], model.uiState.value.portrait)
                assertFalse(model.uiState.value.isLoading)
                assertEquals(days, model.uiState.value.selectedDays)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateResultsFromPreviousPeriodCannotReplaceTheSelectedPeriod() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = mockk<GrowthPortraitStore>()
            val old = MutableStateFlow<ApiResult<GrowthPortraitResponse>>(ApiResult.Loading)
            val recent = mockk<GrowthPortraitResponse>()
            every { store.cached(any()) } returns null
            every { store.observe(30) } returns old
            every { store.observe(7) } returns MutableStateFlow(ApiResult.Success(recent))
            val model = GrowthPortraitViewModel(store)
            runCurrent()
            model.loadPortrait(7)
            runCurrent()
            old.value = ApiResult.Success(mockk())
            runCurrent()
            assertEquals(7, model.uiState.value.selectedDays)
            assertSame(recent, model.uiState.value.portrait)
            assertFalse(model.uiState.value.isLoading)
        } finally { Dispatchers.resetMain() }
    }
}
