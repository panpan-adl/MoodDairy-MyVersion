package com.example.mydiary.ui.calendar

import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.TodoRepository
import com.example.mydiary.data.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRefreshTest {
    @Test fun savedDiaryReturnSelectsItsDateAndMonthAndReloadsRecords() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diaries = mockk<DiaryRepository>()
            val todos = mockk<TodoRepository>()
            val user = mockk<UserRepository>()
            val date = LocalDate.now().minusMonths(2).withDayOfMonth(5)
            every { user.getCurrentUserId() } returns 1L
            every { diaries.getDatesWithDiary(1, any(), any()) } returns flowOf(ApiResult.Success(listOf(date)))
            every { diaries.getAllDiariesByDate(1, date) } returns flowOf(ApiResult.Success(emptyList()))
            every { todos.getTodosByDate(1, date) } returns flowOf(ApiResult.Success(emptyList()))
            val vm = CalendarViewModel(diaries, todos, user)
            vm.showSavedDiary(date); advanceUntilIdle()
            assertEquals(date, vm.uiState.value.selectedDate)
            assertEquals(java.time.YearMonth.from(date), vm.uiState.value.currentMonth)
            assertTrue(date in vm.uiState.value.datesWithDiary)
            verify { diaries.getAllDiariesByDate(1, date) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun returningFromEditorReplacesOldTitleAndReloadsCalendarMarkers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val diaries = mockk<DiaryRepository>()
            val todos = mockk<TodoRepository>()
            val user = mockk<UserRepository>()
            every { user.getCurrentUserId() } returns 1L
            every { diaries.getDatesWithDiary(1, any(), any()) } returns flowOf(ApiResult.Success(emptyList()))
            every { todos.getTodosByDate(any(), any()) } returns flowOf(ApiResult.Success(emptyList()))
            val date = LocalDate.now()
            val old = Diary(10, 1, null, date, emptyList(), null, null, null, null,
                createdAt = null, updatedAt = null)
            every { diaries.getAllDiariesByDate(1, date) } returns flowOf(ApiResult.Success(listOf(old)))
            val vm = CalendarViewModel(diaries, todos, user)
            vm.selectDate(date); advanceUntilIdle()
            assertNull(vm.uiState.value.selectedDateDiaries.single().title)
            every { diaries.getAllDiariesByDate(1, date) } returns
                flowOf(ApiResult.Success(listOf(old.copy(title = "平淡又充实的校园一天"))))
            every { diaries.getDatesWithDiary(1, any(), any()) } returns flowOf(ApiResult.Success(listOf(date)))
            vm.refreshRecords(); advanceUntilIdle()
            assertEquals("平淡又充实的校园一天", vm.uiState.value.selectedDateDiaries.single().title)
            assertEquals(date, vm.uiState.value.selectedDate)
            assertTrue(date in vm.uiState.value.datesWithDiary)
        } finally { Dispatchers.resetMain() }
    }
}
