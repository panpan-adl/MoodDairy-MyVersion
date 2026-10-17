package com.example.mydiary.ui.calendar

import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.TodoRepository
import com.example.mydiary.data.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarLoadingTest {
    private fun model(diaries: DiaryRepository): CalendarViewModel {
        val user = mockk<UserRepository>()
        val todos = mockk<TodoRepository>()
        every { user.getCurrentUserId() } returns 1L
        every { todos.getTodosByDate(any(), any()) } returns flowOf(ApiResult.Success(emptyList()))
        return CalendarViewModel(diaries, todos, user)
    }

    @Test fun stalledServiceStopsBothSpinnersAndRetryRecovers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diaries = mockk<DiaryRepository>()
            every { diaries.getDatesWithDiary(any(), any(), any()) } returns flow { emit(ApiResult.Loading); awaitCancellation() }
            every { diaries.getAllDiariesByDate(any(), any()) } returns flow { emit(ApiResult.Loading); awaitCancellation() }
            val vm = model(diaries)
            val date = LocalDate.now()
            vm.selectDate(date); runCurrent()
            assertTrue(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.isLoadingDiaries)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.isLoadingDiaries)
            assertNotNull(vm.uiState.value.calendarLoadError)
            assertNotNull(vm.uiState.value.diaryLoadError)
            every { diaries.getDatesWithDiary(any(), any(), any()) } returns flowOf(ApiResult.Success(listOf(date)))
            every { diaries.getAllDiariesByDate(any(), any()) } returns flowOf(ApiResult.Success(emptyList()))
            vm.refreshRecords(); advanceUntilIdle()
            assertNull(vm.uiState.value.calendarLoadError)
            assertNull(vm.uiState.value.diaryLoadError)
            assertEquals(setOf(date), vm.uiState.value.datesWithDiary)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun transientConnectionFailureRetriesOnceButAuthFailureDoesNotRetry() = runTest {
        var attempts = 0
        val result = calendarRead {
            attempts++
            flowOf(if (attempts == 1) ApiResult.Error(-1, "offline") else ApiResult.Success(listOf(1)))
        }
        assertEquals(2, attempts)
        assertTrue(result is ApiResult.Success)
        attempts = 0
        calendarRead<Int> { attempts++; flowOf(ApiResult.Error(401, "unauthorized")) }
        assertEquals(1, attempts)
    }

    @Test fun failedRefreshKeepsExistingRecordsAndMonthMarkers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diaries = mockk<DiaryRepository>()
            val date = LocalDate.now()
            val diary = Diary(10, 1, "已有记录", date, emptyList(), null, null, null, null, createdAt = null, updatedAt = null)
            every { diaries.getDatesWithDiary(any(), any(), any()) } returns flowOf(ApiResult.Success(listOf(date)))
            every { diaries.getAllDiariesByDate(any(), any()) } returns flowOf(ApiResult.Success(listOf(diary)))
            val vm = model(diaries)
            vm.selectDate(date); advanceUntilIdle()
            every { diaries.getDatesWithDiary(any(), any(), any()) } returns flow { throw java.io.IOException("offline") }
            every { diaries.getAllDiariesByDate(any(), any()) } returns flow { throw java.io.IOException("offline") }
            vm.refreshRecords(); advanceUntilIdle()
            assertEquals(listOf(diary), vm.uiState.value.selectedDateDiaries)
            assertEquals(setOf(date), vm.uiState.value.datesWithDiary)
            assertNotNull(vm.uiState.value.diaryLoadError)
            assertFalse(vm.uiState.value.isLoadingDiaries)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun switchingDateCancelsOldRequestAndChangingMonthClearsDateLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diaries = mockk<DiaryRepository>()
            val today = LocalDate.now()
            every { diaries.getDatesWithDiary(any(), any(), any()) } returns flowOf(ApiResult.Success(emptyList()))
            every { diaries.getAllDiariesByDate(1, today.minusDays(1)) } returns flow { emit(ApiResult.Loading); awaitCancellation() }
            every { diaries.getAllDiariesByDate(1, today) } returns flowOf(ApiResult.Success(emptyList()))
            val vm = model(diaries)
            vm.selectDate(today.minusDays(1)); runCurrent()
            vm.selectDate(today); advanceUntilIdle()
            assertEquals(today, vm.uiState.value.selectedDate)
            assertFalse(vm.uiState.value.isLoadingDiaries)
            assertNull(vm.uiState.value.diaryLoadError)
            vm.selectDate(today.minusDays(1)); runCurrent()
            vm.previousMonth(); advanceUntilIdle()
            assertNull(vm.uiState.value.selectedDate)
            assertFalse(vm.uiState.value.isLoadingDiaries)
        } finally { Dispatchers.resetMain() }
    }
}
