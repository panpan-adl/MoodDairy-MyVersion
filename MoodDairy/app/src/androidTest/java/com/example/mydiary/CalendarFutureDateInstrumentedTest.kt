package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.mydiary.ui.calendar.CalendarScreen
import com.example.mydiary.ui.calendar.CalendarViewModel
import com.example.mydiary.ui.calendar.FUTURE_DATE_MESSAGE
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarFutureDateInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun futureDateShowsBottomNoticeWithoutSelectionOrNavigation() {
        lateinit var vm: CalendarViewModel
        val navigated = mutableListOf<LocalDate>()
        val future = YearMonth.now().plusMonths(1).atDay(1)
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[CalendarViewModel::class.java]
            vm.nextMonth()
            activity.setContent { MyDiaryTheme { CalendarScreen(vm, { navigated.add(it) }, { _, _ -> }) } }
        }
        compose.onNodeWithTag("calendar-date-$future").performClick()
        compose.onNodeWithText(FUTURE_DATE_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag("calendar-date-$future").performClick()
        compose.runOnIdle {
            assertNull(vm.uiState.value.selectedDate)
            assertFalse(vm.uiState.value.isLoadingDiaries)
            assertTrue(navigated.isEmpty())
        }
        compose.onNodeWithText(FUTURE_DATE_MESSAGE).assertIsDisplayed()
    }

    @Test fun todayAndHistoricalDateKeepNormalSelection() {
        lateinit var vm: CalendarViewModel
        compose.activityRule.scenario.onActivity { activity -> vm = ViewModelProvider(activity)[CalendarViewModel::class.java] }
        compose.runOnIdle {
            assertTrue(vm.selectDate(LocalDate.now()))
            assertEquals(LocalDate.now(), vm.uiState.value.selectedDate)
            assertTrue(vm.selectDate(LocalDate.now().minusDays(1)))
            assertEquals(LocalDate.now().minusDays(1), vm.uiState.value.selectedDate)
        }
    }

    @Test fun greyFutureDateFromNextMonthAlsoExplainsWhyItCannotBeRecorded() {
        var navigated = false
        val future = YearMonth.now().plusMonths(1).atDay(1)
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[CalendarViewModel::class.java]
            activity.setContent { MyDiaryTheme { CalendarScreen(vm, { navigated = true }, { _, _ -> }) } }
        }
        compose.onNodeWithTag("calendar-date-$future").performClick()
        compose.onNodeWithText(FUTURE_DATE_MESSAGE).assertIsDisplayed()
        compose.runOnIdle { assertFalse(navigated) }
    }
}
