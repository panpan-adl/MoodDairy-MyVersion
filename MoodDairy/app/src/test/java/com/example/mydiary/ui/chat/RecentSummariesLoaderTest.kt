package com.example.mydiary.ui.chat

import com.example.mydiary.data.models.DiarySummarySimple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RecentSummariesLoaderTest {
    private val summary = DiarySummarySimple(1, "2026-10-17", "合成检查记录", emptyList(), "平静", 70)

    @Test fun retriesOneInterruptedReadAndReturnsRecoveredSummaries() = runTest {
        var requests = 0
        val loader = RecentSummariesLoader {
            requests++
            if (requests == 1) throw IOException("unexpected end of stream on http://127.0.0.1:8000/")
            Response.success(listOf(summary))
        }
        val result = loader.load(1)
        assertEquals(2, requests)
        assertEquals(listOf(summary), result.summaries)
        assertNull(result.warning)
    }

    @Test fun finalFailureRetainsLastGoodContextAndHidesInternalDetails() = runTest {
        var fail = false
        var requests = 0
        val loader = RecentSummariesLoader {
            requests++
            if (fail) throw IOException("unexpected end of stream on http://127.0.0.1:8000/")
            Response.success(listOf(summary))
        }
        loader.load(1)
        fail = true
        val result = loader.load(1)
        assertEquals(3, requests)
        assertEquals(listOf(summary), result.summaries)
        assertNotNull(result.warning)
        assertFalse(result.warning!!.contains("127.0.0.1"))
        assertFalse(result.warning.contains("stream"))
    }

    @Test fun httpErrorIsNotRetried() = runTest {
        var requests = 0
        val loader = RecentSummariesLoader {
            requests++
            Response.error<List<DiarySummarySimple>>(503, "{\"detail\":\"internal URL\"}".toResponseBody())
        }
        assertNotNull(loader.load(1).warning)
        assertEquals(1, requests)
    }

    @Test fun anotherUserNeverReceivesCachedContext() = runTest {
        val loader = RecentSummariesLoader { id ->
            if (id == 1L) Response.success(listOf(summary)) else throw IOException("offline")
        }
        loader.load(1)
        assertTrue(loader.load(2).summaries.isEmpty())
    }

    @Test fun cancellationDoesNotRetryOrConvertToNetworkError() = runTest {
        var requests = 0
        val loader = RecentSummariesLoader { requests++; throw CancellationException() }
        try { loader.load(1); fail("Cancellation was lost") }
        catch (_: CancellationException) { assertEquals(1, requests) }
    }
}
