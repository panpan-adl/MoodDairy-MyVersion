package com.example.mydiary.ui.chat

import com.example.mydiary.data.models.DiarySummarySimple
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.SafeApiCall
import java.io.IOException
import kotlinx.coroutines.delay
import retrofit2.Response

internal data class SummaryLoadResult(val summaries: List<DiarySummarySimple>, val warning: String? = null)

/** Read-only request; retry one transport interruption and retain this user's last good context. */
internal class RecentSummariesLoader(
    private val read: suspend (Long) -> Response<List<DiarySummarySimple>>,
) {
    private val cache = mutableMapOf<Long, List<DiarySummarySimple>>()

    suspend fun load(userId: Long): SummaryLoadResult {
        var result = SafeApiCall.execute { read(userId) }
        if (result is ApiResult.Error && result.exception is IOException) {
            delay(500)
            result = SafeApiCall.execute { read(userId) }
        }
        return when (result) {
            is ApiResult.Success -> {
                cache[userId] = result.data
                SummaryLoadResult(result.data)
            }
            else -> SummaryLoadResult(cache[userId].orEmpty(), "暂时无法读取日记摘要，请稍后重试。")
        }
    }
}
