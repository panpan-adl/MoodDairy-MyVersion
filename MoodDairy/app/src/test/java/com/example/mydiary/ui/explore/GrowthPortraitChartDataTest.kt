package com.example.mydiary.ui.explore

import com.example.mydiary.data.models.GrowthTrendPointResponse
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GrowthPortraitChartDataTest {
    @Test fun sameDayEntriesBecomeOneDailyAverageInDateOrder() {
        val points = listOf(
            GrowthTrendPointResponse("2026-10-18", 80),
            GrowthTrendPointResponse("2026-10-16", 30),
            GrowthTrendPointResponse("2026-10-18", 40))
        val days = portraitDays(points)
        assertEquals(listOf(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-18")), days.map { it.date })
        assertEquals(listOf(30, 60), days.map { it.value })
        assertEquals(listOf(1, 2), days.map { it.count })
        // The missing 17th is not filled with an invented score.
        assertEquals(2, days.size)
    }

    @Test fun overviewUsesAllEntriesRatherThanUnweightedDailyAverages() {
        val points = listOf(GrowthTrendPointResponse("2026-10-16", 0),
            GrowthTrendPointResponse("2026-10-18", 90), GrowthTrendPointResponse("2026-10-18", 90))
        assertEquals(60, portraitAverage(points))
    }

    @Test fun overviewRoundingMatchesBackendSummary() {
        assertEquals(50, portraitAverage(listOf(GrowthTrendPointResponse("2026-10-18", 50),
            GrowthTrendPointResponse("2026-10-18", 51))))
        assertEquals(52, portraitAverage(listOf(GrowthTrendPointResponse("2026-10-18", 51),
            GrowthTrendPointResponse("2026-10-18", 52))))
    }

    @Test fun emptySingleAndMalformedDatesAreSafe() {
        assertTrue(portraitDays(emptyList()).isEmpty())
        assertNull(portraitAverage(emptyList()))
        val days = portraitDays(listOf(GrowthTrendPointResponse("bad-date", 50), GrowthTrendPointResponse("2026-10-18", 150)))
        assertEquals(1, days.size)
        assertEquals(100, days.single().value)
        assertEquals(1, days.single().count)
    }
}
