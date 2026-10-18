package com.example.mydiary.ui.relax

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.DiaryApiService
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RelaxRecommendationsRepositoryTest {
    private val tracks = listOf(RelaxTrack("1", "Music", "Artist", "https://music.163.com/song?id=1"))
    private val api = mockk<DiaryApiService>()
    private fun repository(cached: Boolean, fresh: Boolean = false): RelaxRecommendationsRepository {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString("tracks", null) } returns if (cached) Gson().toJson(RelaxRecommendations(tracks)) else null
        every { prefs.getLong("fetched_at", 0L) } returns if (fresh) System.currentTimeMillis() else 0L
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        return RelaxRecommendationsRepository(api, context)
    }
    @Test fun savedListIsImmediatelyVisibleAndFreshCacheSkipsNetwork() = runTest {
        val repo = repository(cached = true, fresh = true)
        assertEquals(tracks, repo.state.value.tracks)
        repo.refresh()
        coVerify(exactly = 0) { api.relaxRecommendations() }
    }
    @Test fun failedRefreshKeepsCachedListPlayable() = runTest {
        coEvery { api.relaxRecommendations() } throws java.io.IOException()
        val repo = repository(cached = true)
        repo.refresh()
        assertEquals(tracks, repo.state.value.tracks)
        assertFalse(repo.state.value.loading)
        assertNotNull(repo.state.value.error)
    }
    @Test fun overlappingRequestsDoNotClearCachedListOrDuplicateCalls() = runTest {
        val pending = CompletableDeferred<Response<RelaxRecommendations>>()
        coEvery { api.relaxRecommendations() } coAnswers { pending.await() }
        val repo = repository(cached = true)
        val first = launch { repo.refresh() }
        runCurrent()
        assertTrue(repo.state.value.loading)
        assertEquals(tracks, repo.state.value.tracks)
        repo.refresh()
        coVerify(exactly = 1) { api.relaxRecommendations() }
        pending.complete(Response.success(RelaxRecommendations(tracks)))
        first.join()
        assertFalse(repo.state.value.loading)
        assertNull(repo.state.value.error)
    }
    @Test fun firstLoadTimesOutAndCanRetry() = runTest {
        coEvery { api.relaxRecommendations() } coAnswers { awaitCancellation() }
        val repo = repository(cached = false)
        val loading = launch { repo.refresh() }
        advanceUntilIdle()
        loading.join()
        assertFalse(repo.state.value.loading)
        assertNotNull(repo.state.value.error)
        coEvery { api.relaxRecommendations() } returns Response.success(RelaxRecommendations(tracks))
        repo.refresh(force = true)
        assertEquals(tracks, repo.state.value.tracks)
        assertNull(repo.state.value.error)
    }
    @Test fun explicitRetryRefreshesEvenFreshCache() = runTest {
        val repo = repository(cached = true, fresh = true)
        coEvery { api.relaxRecommendations() } throws java.io.IOException()
        repo.refresh(force = true)
        coVerify(exactly = 1) { api.relaxRecommendations() }
        assertEquals(tracks, repo.state.value.tracks)
    }
}
