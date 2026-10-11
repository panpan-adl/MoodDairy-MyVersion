package com.example.mydiary.data.repository

import android.util.Log
import com.example.mydiary.data.models.ChatStreamEvent
import com.example.mydiary.data.models.FaceEmotionReportResponse
import com.example.mydiary.data.network.DiaryApiService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class EmotionSignalRepositoryTest {
    @Test fun changingSmileSubtypeIsReportedWithoutWaitingSixtySeconds() = runTest {
        val api = mockk<DiaryApiService>()
        coEvery { api.reportFaceEmotion(any()) } returns Response.success(FaceEmotionReportResponse(true))
        val repository = EmotionSignalRepository(api)
        assertNotNull(repository.report("happy", .8f, facialExpression = "smile"))
        assertNotNull(repository.report("happy", .9f, facialExpression = "broad_smile"))
        assertNull(repository.report("happy", .9f, facialExpression = "broad_smile"))
        coVerify(exactly = 1) { api.reportFaceEmotion(match { it.emotion == "happy" && it.facialExpression == "broad_smile" }) }
    }
    @Before fun prepareLogs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun releaseLogs() { unmockkStatic(Log::class) }

    @Test fun sustainedExpressionBypassesNormalSixtySecondDeduplication() = runTest {
        val api = mockk<DiaryApiService>()
        coEvery { api.reportFaceEmotion(any()) } returns Response.success(FaceEmotionReportResponse(true))
        val repository = EmotionSignalRepository(api)
        repository.report("sad", 0.8f)
        assertNull(repository.report("sad", 0.8f))
        assertNotNull(repository.report("sad", 0.8f, 10_000L))
        coVerify(exactly = 1) { api.reportFaceEmotion(match { it.sustainedNegativeMs == 10_000L }) }
        coVerify(exactly = 2) { api.reportFaceEmotion(any()) }
    }

    @Test fun successfulPromptDoesNotRepeatOnEveryCameraFrame() = runTest {
        val api = mockk<DiaryApiService>()
        coEvery { api.reportFaceEmotion(any()) } returns Response.success(
            FaceEmotionReportResponse(true, serviceBundle = ChatStreamEvent("service_bundle", bundleId = "test"))
        )
        val repository = EmotionSignalRepository(api)
        assertNotNull(repository.report("sad", 0.8f, 10_000L))
        assertNull(repository.report("sad", 0.8f, 12_000L))
        coVerify(exactly = 1) { api.reportFaceEmotion(any()) }
    }
}
