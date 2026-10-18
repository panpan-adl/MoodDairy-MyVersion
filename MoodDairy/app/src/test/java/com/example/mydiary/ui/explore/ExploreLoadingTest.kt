package com.example.mydiary.ui.explore

import com.example.mydiary.data.local.ExploreCache
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.DiaryResponse
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreLoadingTest {
    private val api = mockk<DiaryApiService>()
    private val user = mockk<UserRepository> { every { getCurrentUserId() } returns 7L }
    private val cache = mockk<ExploreCache>(relaxed = true)

    @Test fun cachedContentIsVisibleBeforeAnyNetworkWorkAndSurvivesFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diary = mockk<Diary>()
            every { cache.load(7, "highlights") } returns listOf(diary)
            every { cache.load(7, "littleJoys") } returns emptyList()
            coEvery { api.getHighlightDiaries(7, 10) } coAnswers { delay(1000); throw java.io.IOException("offline") }
            coEvery { api.getLittleJoyDiaries(7, 10) } throws java.io.IOException("offline")
            val vm = ExploreViewModel(api, user, cache, mockk(relaxed = true))
            assertEquals(listOf(diary), vm.uiState.value.highlightDiaries)
            assertFalse(vm.uiState.value.isLoadingHighlights)
            assertFalse(vm.uiState.value.isLoadingLittleJoys)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(diary), vm.uiState.value.highlightDiaries)
            coVerify(exactly = 1) { api.getHighlightDiaries(7, 10) }
            verify(exactly = 0) { cache.save(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun fastSectionFinishesWithoutWaitingForSlowSection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            every { cache.load(any(), any()) } returns null
            coEvery { api.getHighlightDiaries(7, 10) } coAnswers { delay(100); Response.success(emptyList<DiaryResponse>()) }
            coEvery { api.getLittleJoyDiaries(7, 10) } coAnswers { delay(5000); Response.success(emptyList<DiaryResponse>()) }
            val vm = ExploreViewModel(api, user, cache, mockk(relaxed = true))
            runCurrent()
            advanceTimeBy(101)
            assertFalse(vm.uiState.value.isLoadingHighlights)
            assertTrue(vm.uiState.value.isLoadingLittleJoys)
            verify { cache.save(7, "highlights", emptyList()) }
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoadingLittleJoys)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun successfulEmptyResponseReplacesOldContentAndRefreshKeepsItVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            every { cache.load(7, "highlights") } returns listOf(mockk())
            every { cache.load(7, "littleJoys") } returns emptyList()
            coEvery { api.getHighlightDiaries(7, 10) } returns Response.success(emptyList())
            coEvery { api.getLittleJoyDiaries(7, 10) } returns Response.success(emptyList())
            val vm = ExploreViewModel(api, user, cache, mockk(relaxed = true))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.highlightDiaries.isEmpty())
            verify { cache.save(7, "highlights", emptyList()) }
            vm.refresh()
            assertFalse(vm.uiState.value.isLoadingHighlights)
            advanceUntilIdle()
            coVerify(exactly = 2) { api.getHighlightDiaries(7, 10) }
        } finally { Dispatchers.resetMain() }
    }
}
