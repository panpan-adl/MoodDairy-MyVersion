package com.example.mydiary.ui.editor

import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.GrowthPortraitRepository
import com.example.mydiary.ui.drawing.DrawingNavigationState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryReviewFlowTest {
    private fun model(repo: GrowthPortraitRepository): Pair<DiaryEditorViewModel, MutableStateFlow<DiaryEditorUiState>> {
        val vm = DiaryEditorViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), repo, mockk(relaxed = true), DrawingNavigationState())
        val field = DiaryEditorViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<DiaryEditorUiState>
        state.value = DiaryEditorUiState(diaryId = 10, title = "报告")
        return vm to state
    }

    @Test fun unsavedDraftNeverCallsReadOrGenerate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            val (vm, state) = model(repo)
            state.value = state.value.copy(hasUnsavedChanges = true)
            vm.generateDiaryReview()
            advanceUntilIdle()
            assertEquals("先保存这篇日记，再一起回顾吧。", vm.uiState.value.reviewError)
            coVerify(exactly = 0) { repo.getDiaryReview(any()) }
            coVerify(exactly = 0) { repo.generateDiaryReview(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun repeatedClickMakesOneRequestAndEditingMarksLateResultStale() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            val response = DiaryReviewResponse(10, "v1", body = "完成报告后很累。")
            val pending = CompletableDeferred<ApiResult<DiaryReviewResponse>>()
            coEvery { repo.getDiaryReview(10) } returns ApiResult.Success(response.copy(body = null))
            coEvery { repo.generateDiaryReview(10, "v1", false) } coAnswers { pending.await() }
            val (vm, state) = model(repo)
            vm.generateDiaryReview(); vm.generateDiaryReview()
            runCurrent()
            state.value = state.value.copy(title = "改过的标题", hasUnsavedChanges = true)
            pending.complete(ApiResult.Success(response))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.diaryReview!!.stale)
            assertFalse(vm.uiState.value.reviewGenerating)
            coVerify(exactly = 1) { repo.generateDiaryReview(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun editingDuringCacheReadStopsBeforeCallingModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            val pending = CompletableDeferred<ApiResult<DiaryReviewResponse>>()
            coEvery { repo.getDiaryReview(10) } coAnswers { pending.await() }
            val (vm, state) = model(repo)
            vm.generateDiaryReview(); runCurrent()
            state.value = state.value.copy(hasUnsavedChanges = true)
            pending.complete(ApiResult.Success(DiaryReviewResponse(10, "v1")))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.reviewGenerating)
            assertEquals("先保存这篇日记，再一起回顾吧。", vm.uiState.value.reviewError)
            coVerify(exactly = 0) { repo.generateDiaryReview(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun mediaOnlyDoesNotCallModelAndFailurePreservesExistingReview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<GrowthPortraitRepository>()
            coEvery { repo.getDiaryReview(10) } returns ApiResult.Success(DiaryReviewResponse(10, "v1", hasText = false))
            val (vm, state) = model(repo)
            vm.generateDiaryReview(); advanceUntilIdle()
            assertFalse(vm.uiState.value.diaryReview!!.hasText)
            coVerify(exactly = 0) { repo.generateDiaryReview(any(), any(), any()) }
            val old = DiaryReviewResponse(10, "v1", body = "原来的回顾")
            state.value = state.value.copy(diaryReview = old)
            coEvery { repo.getDiaryReview(10) } returns ApiResult.Success(old)
            coEvery { repo.generateDiaryReview(10, "v1", true) } returns ApiResult.Error(503, "failure")
            vm.generateDiaryReview(); advanceUntilIdle()
            assertEquals(old, vm.uiState.value.diaryReview)
            assertEquals("这次没能整理出来，你写下的内容还在，可以稍后再试。", vm.uiState.value.reviewError)
        } finally { Dispatchers.resetMain() }
    }
}
