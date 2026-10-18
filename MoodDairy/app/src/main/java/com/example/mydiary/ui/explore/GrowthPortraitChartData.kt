package com.example.mydiary.ui.explore

import com.example.mydiary.data.models.GrowthTrendPointResponse
import java.time.LocalDate
import kotlin.math.round

internal data class PortraitDay(val date: LocalDate, val value: Int, val count: Int)

/** Several diary entries on one day become one point; unrecorded days stay absent. */
internal fun portraitDays(points: List<GrowthTrendPointResponse>): List<PortraitDay> =
    points.mapNotNull { point ->
        runCatching { LocalDate.parse(point.date) to point.value.coerceIn(0, 100) }.getOrNull()
    }.groupBy({ it.first }, { it.second }).toSortedMap().map { (date, values) ->
        PortraitDay(date, round(values.average()).toInt(), values.size)
    }

internal fun portraitAverage(points: List<GrowthTrendPointResponse>): Int? =
    points.takeIf { it.isNotEmpty() }?.map { it.value.coerceIn(0, 100) }?.average()?.let { round(it).toInt() }
