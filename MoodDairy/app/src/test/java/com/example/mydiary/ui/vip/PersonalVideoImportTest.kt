package com.example.mydiary.ui.vip

import com.example.mydiary.data.network.DiaryApiService
import com.google.gson.JsonObject
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalVideoImportTest {
    @Before fun prepare() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun finish() { Dispatchers.resetMain() }
    private fun page(next: String, more: Boolean) = Response.success(JsonObject().apply {
        addProperty("next_cursor", next); addProperty("has_more", more); addProperty("imported", 20)
    })
    @Test fun extractsOnlyTrustedFullLinksAndIds() {
        assertEquals("MS4wabc_123-", VideoImportSource.parse("douyin", "主页 https://www.douyin.com/user/MS4wabc_123-?from=share"))
        assertEquals("123", VideoImportSource.parse("bilibili", "https://space.bilibili.com/4/favlist?fid=%31%32%33&ftype=create"))
        assertNull(VideoImportSource.parse("douyin", "https://www.douyin.com.evil.com/user/MS4wabc"))
        assertNull(VideoImportSource.parse("douyin", "https://v.douyin.com/abc"))
        assertNull(VideoImportSource.parse("bilibili", "https://evil.com/?fid=123"))
    }
    @Test fun batchStopsAtEndWithoutBuyingMorePages() = runTest {
        val api = mockk<DiaryApiService>()
        coEvery { api.importPersonalVideos(any()) } returnsMany listOf(page("2", true), page("3", false))
        val vm = PersonalVideoViewModel(api)
        vm.importPages("bilibili", "", "", "123", 10)
        advanceUntilIdle()
        coVerify(exactly = 2) { api.importPersonalVideos(any()) }
        assertTrue(vm.completed.value)
        vm.importPages("bilibili", "", "", "123", 10)
        advanceUntilIdle()
        coVerify(exactly = 2) { api.importPersonalVideos(any()) }
    }
    @Test fun stoppingFinishesCurrentPageAndResumesFromNext() = runTest {
        val api = mockk<DiaryApiService>()
        val vm = PersonalVideoViewModel(api)
        val cursors = mutableListOf<String>()
        coEvery { api.importPersonalVideos(any()) } coAnswers {
            cursors.add(firstArg<JsonObject>().get("cursor").asString)
            vm.stop()
            page("2", true)
        }
        vm.importPages("bilibili", "", "", "123", 10)
        advanceUntilIdle()
        assertEquals(listOf("0"), cursors)
        coEvery { api.importPersonalVideos(any()) } coAnswers {
            cursors.add(firstArg<JsonObject>().get("cursor").asString)
            page("3", false)
        }
        vm.importPages("bilibili", "", "", "123", 10)
        advanceUntilIdle()
        assertEquals(listOf("0", "2"), cursors)
    }
    @Test fun failedPageRetainsCursorForRetry() = runTest {
        val api = mockk<DiaryApiService>()
        val cursors = mutableListOf<String>()
        coEvery { api.importPersonalVideos(any()) } coAnswers {
            val cursor = firstArg<JsonObject>().get("cursor").asString
            cursors.add(cursor)
            if (cursor == "0") page("2", true) else throw java.io.IOException("offline")
        }
        val vm = PersonalVideoViewModel(api)
        vm.importPages("bilibili", "", "", "123", 3)
        advanceUntilIdle()
        assertEquals("2", vm.cursor.value)
        assertFalse(vm.busy.value)
        coEvery { api.importPersonalVideos(any()) } coAnswers {
            cursors.add(firstArg<JsonObject>().get("cursor").asString); page("3", false)
        }
        vm.importPages("bilibili", "", "", "123", 3)
        advanceUntilIdle()
        assertEquals(listOf("0", "2", "2"), cursors)
    }
    @Test fun repeatedCursorAndDoubleClickNeverStartMoreRequests() = runTest {
        val api = mockk<DiaryApiService>()
        coEvery { api.importPersonalVideos(any()) } returns page("0", true)
        val vm = PersonalVideoViewModel(api)
        vm.importPages("bilibili", "", "", "123", 10)
        vm.importPages("bilibili", "", "", "123", 10)
        advanceUntilIdle()
        coVerify(exactly = 1) { api.importPersonalVideos(any()) }
        assertTrue(vm.message.value.contains("分页没有前进"))
    }
    @Test fun changingSourceRestartsFromFirstPage() = runTest {
        val api = mockk<DiaryApiService>()
        val cursors = mutableListOf<String>()
        coEvery { api.importPersonalVideos(any()) } coAnswers {
            cursors.add(firstArg<JsonObject>().get("cursor").asString); page("2", true)
        }
        val vm = PersonalVideoViewModel(api)
        vm.importPages("bilibili", "", "", "123", 1)
        advanceUntilIdle()
        vm.importPages("bilibili", "", "", "456", 1)
        advanceUntilIdle()
        assertEquals(listOf("0", "0"), cursors)
    }
}
