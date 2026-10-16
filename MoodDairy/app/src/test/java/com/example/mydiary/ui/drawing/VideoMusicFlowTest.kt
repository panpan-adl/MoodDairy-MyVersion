package com.example.mydiary.ui.drawing

import com.example.mydiary.data.models.VideoStatusResponse
import com.example.mydiary.data.models.VideoTaskResponse
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.repository.DrawingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class VideoMusicFlowTest {
    @Test fun pollingContinuesUntilMusicIsReadyAndReportsProgress() = runTest {
        val api = mockk<DiaryApiService>()
        val processing = VideoStatusResponse("qa", "processing", audioStatus = "processing")
        val ready = VideoStatusResponse("qa", "succeeded", videoUrl = "https://example.com/music.mp4", audioStatus = "ready")
        coEvery { api.getVideoStatus("qa") } returnsMany listOf(Response.success(processing), Response.success(ready))
        val states = mutableListOf<String?>()
        val result = DrawingRepository(api).waitForVideo("qa", pollIntervalMs = 1, onProgress = { states.add(it.audioStatus) })
        assertEquals(listOf("processing", "ready"), states)
        assertEquals(ready.videoUrl, result.getOrThrow().videoUrl)
        coVerify(exactly = 2) { api.getVideoStatus("qa") }
    }

    @Test fun failedMusicRetryUsesSameTaskWithoutAnotherPaidGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = mockk<DiaryApiService>()
            val failed = VideoStatusResponse("qa", "failed", audioStatus = "failed", error = "视频已生成，但背景音乐添加失败，请重试。")
            val ready = VideoStatusResponse("qa", "succeeded", videoUrl = "https://example.com/music.mp4", audioStatus = "ready")
            coEvery { api.createVideo(any()) } returns Response.success(VideoTaskResponse("qa", "pending"))
            coEvery { api.getVideoStatus("qa") } returnsMany listOf(Response.success(failed), Response.success(ready))
            coEvery { api.retryVideoAudio("qa") } returns Response.success(VideoStatusResponse("qa", "processing", audioStatus = "processing"))
            val vm = DrawingViewModel(DrawingRepository(api))
            vm.setGeneratedImage("https://example.com/picture.png")
            vm.createVideo()
            advanceUntilIdle()
            assertEquals("failed", vm.uiState.value.videoAudioStatus)
            assertNull(vm.uiState.value.videoUrl)
            vm.retryVideoAudio()
            advanceUntilIdle()
            assertEquals(ready.videoUrl, vm.uiState.value.videoUrl)
            assertEquals("ready", vm.uiState.value.videoAudioStatus)
            coVerify(exactly = 1) { api.createVideo(any()) }
            coVerify(exactly = 1) { api.retryVideoAudio("qa") }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun changingImageIgnoresLateVideoReplyAndClearsRetryState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = mockk<DiaryApiService>()
            val late = CompletableDeferred<VideoStatusResponse>()
            coEvery { api.createVideo(any()) } returns Response.success(VideoTaskResponse("old", "pending"))
            coEvery { api.getVideoStatus("old") } coAnswers { Response.success(late.await()) }
            val vm = DrawingViewModel(DrawingRepository(api))
            vm.setGeneratedImage("https://example.com/first.png")
            vm.createVideo()
            runCurrent()
            vm.setGeneratedImage("https://example.com/second.png")
            late.complete(VideoStatusResponse("old", "succeeded", videoUrl = "https://example.com/old.mp4", audioStatus = "ready"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.videoUrl)
            assertNull(vm.uiState.value.videoTaskId)
            assertNull(vm.uiState.value.videoAudioStatus)
            assertFalse(vm.uiState.value.isCreatingVideo)
        } finally { Dispatchers.resetMain() }
    }
}
