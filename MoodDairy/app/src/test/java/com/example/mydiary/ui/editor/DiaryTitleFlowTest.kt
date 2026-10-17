package com.example.mydiary.ui.editor

import android.util.Log
import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.GrowthPortraitRepository
import com.example.mydiary.ui.drawing.DrawingNavigationState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryTitleFlowTest {
    private fun model(repo: GrowthPortraitRepository, diaries: DiaryRepository): Pair<DiaryEditorViewModel, MutableStateFlow<DiaryEditorUiState>> {
        val vm = DiaryEditorViewModel(diaries, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), repo, mockk(relaxed = true), DrawingNavigationState())
        val field = DiaryEditorViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<DiaryEditorUiState>
        state.value = DiaryEditorUiState(diaryId = 10, mediaItems = listOf(
            MediaItem.Text("1", content = "清晨买豆浆去上课。", order = 0, createdAt = Instant.EPOCH)))
        return vm to state
    }

    @Test fun recommendationWaitsForConfirmationAndSavesUserEditedTitle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        try {
            val repo = mockk<GrowthPortraitRepository>()
            val diaries = mockk<DiaryRepository>()
            coEvery { repo.recommendDiaryTitle(any()) } returns ApiResult.Success(TitleRecommendationResponse("清晨的豆浆"))
            coEvery { repo.getDiaryReview(10) } returns ApiResult.Error(503, "unavailable")
            val saved = mockk<Diary> { every { id } returns 10L }
            every { diaries.updateDiary(10, any()) } returns flowOf(ApiResult.Success(saved))
            val (vm, state) = model(repo, diaries)
            every { diaries.syncMediaItems(10, any()) } returns flowOf(ApiResult.Success(state.value.mediaItems))
            vm.saveDiary(); vm.saveDiary(); advanceUntilIdle()
            assertEquals("清晨的豆浆", state.value.suggestedTitle)
            assertEquals("", state.value.title)
            verify(exactly = 0) { diaries.updateDiary(any(), any()) }
            coVerify(exactly = 1) { repo.recommendDiaryTitle(any()) }
            vm.editSuggestedTitle("我的匆忙早晨")
            vm.saveWithSuggestedTitle(true); advanceUntilIdle()
            verify(exactly = 1) { diaries.updateDiary(10, match { it.title == "我的匆忙早晨" }) }
            assertTrue(state.value.saveSuccess)
        } finally { unmockkStatic(Log::class); Dispatchers.resetMain() }
    }

    @Test fun cancelledRequestCannotReplaceNewerSuggestion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            val first = CompletableDeferred<ApiResult<TitleRecommendationResponse>>()
            coEvery { repo.recommendDiaryTitle(any()) } coAnswers { first.await() }
            val (vm, state) = model(repo, mockk())
            vm.saveDiary(); runCurrent(); vm.dismissTitleSuggestion()
            coEvery { repo.recommendDiaryTitle(any()) } returns ApiResult.Success(TitleRecommendationResponse("新推荐"))
            vm.saveDiary(); runCurrent()
            first.complete(ApiResult.Success(TitleRecommendationResponse("旧推荐")))
            advanceUntilIdle()
            assertEquals("新推荐", state.value.suggestedTitle)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failureLeavesDraftEditableAndChangedContentRejectsLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            coEvery { repo.recommendDiaryTitle(any()) } returns ApiResult.Error(503, "unavailable")
            val (vm, state) = model(repo, mockk())
            vm.saveDiary(); advanceUntilIdle()
            assertNotNull(state.value.titleSuggestionError)
            assertTrue(state.value.showTitleSuggestion)
            assertFalse(state.value.isSaving)
            vm.dismissTitleSuggestion()
            val pending = CompletableDeferred<ApiResult<TitleRecommendationResponse>>()
            coEvery { repo.recommendDiaryTitle(any()) } coAnswers { pending.await() }
            vm.saveDiary(); runCurrent()
            state.value = state.value.copy(mediaItems = emptyList())
            pending.complete(ApiResult.Success(TitleRecommendationResponse("过期标题")))
            advanceUntilIdle()
            assertFalse(state.value.showTitleSuggestion)
            assertEquals("", state.value.title)
        } finally { Dispatchers.resetMain() }
    }
}
