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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DiarySaveFlowTest {
    private fun text(id: String = "text", content: String = "旧文字", order: Int = 0) =
        MediaItem.Text(id, content = content, order = order, createdAt = Instant.EPOCH)
    private data class Harness(val vm: DiaryEditorViewModel, val state: MutableStateFlow<DiaryEditorUiState>,
        val diaries: DiaryRepository, val reviews: GrowthPortraitRepository)

    private fun TestScope.harness(): Harness {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val diaries = mockk<DiaryRepository>()
        val reviews = mockk<GrowthPortraitRepository>()
        val vm = DiaryEditorViewModel(diaries, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), reviews, mockk(relaxed = true), DrawingNavigationState())
        val field = DiaryEditorViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(vm) as MutableStateFlow<DiaryEditorUiState>
        state.value = DiaryEditorUiState(diaryId = 10, title = "标题", mediaItems = listOf(text()),
            confirmedText = mapOf("text" to "旧文字"))
        val saved = Diary(10, 1, "标题", LocalDate.now(), emptyList(), null, null, null, null, createdAt = null, updatedAt = null)
        every { diaries.updateDiary(any(), any()) } returns flowOf(ApiResult.Success(saved))
        every { diaries.createDiary(any()) } returns flowOf(ApiResult.Success(saved))
        every { diaries.syncMediaItems(any(), any()) } answers {
            flowOf(ApiResult.Success(secondArg<List<MediaItem>>().mapIndexed { index, item ->
                if (item is MediaItem.Text) item.copy(id = "saved_$index") else item
            }))
        }
        coEvery { reviews.getDiaryReview(any()) } returns ApiResult.Error(503, "not available")
        return Harness(vm, state, diaries, reviews)
    }
    private fun cleanup() { unmockkStatic(Log::class); Dispatchers.resetMain() }

    @Test fun topSaveCompletesOnceWithCalendarDestinationAndNoDrawingPrompt() = runTest {
        val (vm, state, diaries) = harness()
        try {
            vm.saveDiary(); vm.saveDiary(); advanceUntilIdle()
            verify(exactly = 1) { diaries.updateDiary(any(), any()) }
            assertEquals(DiarySaveAction.ReturnToCalendar, state.value.saveCompletion!!.action)
            assertEquals(state.value.date, vm.consumeSaveCompletion(true)!!.date)
            assertNull(state.value.drawingPromptDiaryId)
            assertNull(vm.consumeSaveCompletion(true))
        } finally { cleanup() }
    }

    @Test fun onlyChangedNonEmptyTextCanConfirmAndCancelCountsAsConfirmation() = runTest {
        val (vm, state, diaries) = harness()
        try {
            assertFalse(state.value.canConfirmText("text"))
            vm.confirmText("text")
            verify(exactly = 0) { diaries.updateDiary(any(), any()) }
            vm.updateTextContent("text", "新文字")
            assertTrue(state.value.canConfirmText("text"))
            vm.confirmText("text"); vm.confirmText("text"); advanceUntilIdle()
            assertTrue(state.value.saveCompletion!!.action is DiarySaveAction.ConfirmText)
            vm.consumeSaveCompletion(true)
            assertEquals(10L, state.value.drawingPromptDiaryId)
            assertNull(vm.consumeSaveCompletion(true)) // recreating the UI cannot replay completion
            vm.dismissDrawingPrompt()
            assertFalse(state.value.canConfirmText("saved_0"))
            vm.updateTextContent("saved_0", "再修改一次")
            assertTrue(state.value.canConfirmText("saved_0"))
            vm.updateTextContent("saved_0", "  ")
            assertFalse(state.value.canConfirmText("saved_0"))
        } finally { cleanup() }
    }

    @Test fun confirmingOneCardDoesNotConsumeOtherCardsAndDrawingSwitchIsRespected() = runTest {
        val (vm, state) = harness()
        try {
            state.value = state.value.copy(mediaItems = listOf(text(content = "新文字"), text("second", "第二段", 1)))
            vm.confirmText("text"); advanceUntilIdle(); vm.consumeSaveCompletion(false)
            assertNull(state.value.drawingPromptDiaryId)
            assertFalse(state.value.canConfirmText("saved_0"))
            assertTrue(state.value.canConfirmText("saved_1"))
            vm.updateTitle("只改标题")
            assertFalse(state.value.canConfirmText("saved_0"))
        } finally { cleanup() }
    }

    @Test fun titleRecommendationKeepsTextIntentAndCancellationCannotLaterSave() = runTest {
        val (vm, state, diaries, reviews) = harness()
        try {
            state.value = state.value.copy(title = "", mediaItems = listOf(text(content = "新文字")))
            coEvery { reviews.recommendDiaryTitle(any()) } returns ApiResult.Success(TitleRecommendationResponse("推荐标题"))
            vm.confirmText("text"); advanceUntilIdle()
            assertTrue(state.value.showTitleSuggestion)
            assertFalse(state.value.canConfirmText("text"))
            vm.dismissTitleSuggestion(); vm.saveWithSuggestedTitle(true); advanceUntilIdle()
            verify(exactly = 0) { diaries.updateDiary(any(), any()) }
            vm.confirmText("text"); advanceUntilIdle()
            vm.saveWithSuggestedTitle(true); advanceUntilIdle()
            assertEquals("推荐标题", state.value.title)
            assertTrue(state.value.saveCompletion!!.action is DiarySaveAction.ConfirmText)
        } finally { cleanup() }
    }

    @Test fun editingAfterTitleSuggestionRequiresTextReconfirmation() = runTest {
        val (vm, state, diaries, reviews) = harness()
        try {
            state.value = state.value.copy(title = "", mediaItems = listOf(text(content = "新文字")))
            coEvery { reviews.recommendDiaryTitle(any()) } returns ApiResult.Success(TitleRecommendationResponse("推荐标题"))
            vm.confirmText("text"); advanceUntilIdle()
            vm.updateTextContent("text", "推荐后又改过")
            vm.saveWithSuggestedTitle(true); advanceUntilIdle()
            verify(exactly = 0) { diaries.updateDiary(any(), any()) }
            assertNull(state.value.saveCompletion)
            assertTrue(state.value.canConfirmText("text"))
        } finally { cleanup() }
    }

    @Test fun metadataAndSyncFailuresLeaveTextRetryableAndDoNotCreateDuplicateDiaries() = runTest {
        val (vm, state, diaries) = harness()
        try {
            vm.updateTextContent("text", "新文字")
            every { diaries.updateDiary(any(), any()) } returns flowOf(ApiResult.Error(503, "failed"))
            vm.confirmText("text"); advanceUntilIdle()
            assertNull(state.value.saveCompletion)
            assertTrue(state.value.canConfirmText("text"))
            state.value = state.value.copy(diaryId = null)
            every { diaries.syncMediaItems(any(), any()) } returns flowOf(ApiResult.Error(503, "failed"))
            vm.confirmText("text"); advanceUntilIdle()
            assertEquals(10L, state.value.diaryId)
            assertNull(state.value.saveCompletion)
            assertTrue(state.value.hasUnsavedChanges)
            vm.confirmText("text"); advanceUntilIdle()
            verify(exactly = 1) { diaries.createDiary(any()) }
        } finally { cleanup() }
    }

    @Test fun editsDuringSyncKeepNewTextAdoptServerIdAndSuppressOldNavigation() = runTest {
        val (vm, state, diaries) = harness()
        try {
            vm.updateTextContent("text", "准备保存")
            val result = CompletableDeferred<ApiResult<List<MediaItem>>>()
            every { diaries.syncMediaItems(any(), any()) } returns flow { emit(result.await()) }
            vm.confirmText("text"); runCurrent()
            vm.updateTextContent("text", "同步期间继续修改")
            result.complete(ApiResult.Success(listOf(text("server_id", "准备保存"))))
            advanceUntilIdle()
            assertEquals("同步期间继续修改", (state.value.mediaItems.single() as MediaItem.Text).content)
            assertEquals("server_id", state.value.mediaItems.single().id)
            assertTrue(state.value.hasUnsavedChanges)
            assertNull(state.value.saveCompletion)
            assertTrue(state.value.canConfirmText("server_id"))
        } finally { cleanup() }
    }
    @Test fun connectionLossKeepsNewDiaryDraftAndDoesNotReplayTheSave() = runTest {
        val (vm, state, diaries) = harness()
        try {
            state.value = state.value.copy(diaryId = null, hasUnsavedChanges = true)
            val original = state.value
            every { diaries.createDiary(any()) } returns flowOf(ApiResult.Error(-1, "interrupted", java.io.IOException("EOF")))
            vm.saveDiary(); advanceUntilIdle()
            verify(exactly = 1) { diaries.createDiary(any()) }
            verify(exactly = 0) { diaries.syncMediaItems(any(), any()) }
            assertEquals(original.title, state.value.title)
            assertEquals(original.mediaItems, state.value.mediaItems)
            assertNull(state.value.diaryId)
            assertNull(state.value.saveCompletion)
            assertFalse(state.value.isSaving)
            assertTrue(state.value.hasUnsavedChanges)
            assertTrue(state.value.errorMessage!!.contains("保存结果未确认"))
        } finally { cleanup() }
    }

}
