package com.example.mydiary.data.repository

import com.example.mydiary.data.local.GrowthPortraitCache
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class GrowthPortraitStoreTest {
    private val day = LocalDate.now().toString()
    private val repository = mockk<GrowthPortraitRepository>()
    private val users = mockk<UserRepository>()
    private val cache = mockk<GrowthPortraitCache>(relaxed = true)
    private fun portrait(user: Long = 7): GrowthPortraitResponse = mockk {
        every { userId } returns user
        every { rangeEnd } returns day
    }
    private fun setup() {
        every { users.getCurrentUserId() } returns 7L
        every { cache.load(any(), any(), any()) } returns null
    }

    @Test fun prefetchLoadsThreePeriodsAndOpeningJoinsTheExistingRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            setup()
            val value = portrait()
            every { repository.getGrowthPortrait(any(), day) } returns flow {
                emit(ApiResult.Loading); delay(1000); emit(ApiResult.Success(value))
            }
            val store = GrowthPortraitStore(repository, users, cache)
            store.prefetch()
            runCurrent()
            val opening = store.observe(30)
            store.prefetch()
            advanceUntilIdle()
            assertSame(value, (opening.value as ApiResult.Success).data)
            listOf(7, 30, 90).forEach {
                assertSame(value, store.cached(it))
                store.observe(it)
                verify(exactly = 1) { repository.getGrowthPortrait(it, day) }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun persistedSnapshotsDisplayImmediatelyAndSurviveNetworkFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            setup()
            val value = portrait()
            every { cache.load(7, 30, day) } returns value
            every { repository.getGrowthPortrait(30, day) } returns flow {
                delay(100); emit(ApiResult.Error(-1, "offline"))
            }
            val store = GrowthPortraitStore(repository, users, cache)
            assertSame(value, store.cached(30))
            val state = store.observe(30)
            assertSame(value, (state.value as ApiResult.Success).data)
            advanceUntilIdle()
            assertSame(value, store.cached(30))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun accountSwitchDoesNotReuseOrPublishAnotherAccountsSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            setup()
            val first = portrait()
            every { repository.getGrowthPortrait(30, day) } returns flow {
                delay(1000); emit(ApiResult.Success(first))
            }
            val store = GrowthPortraitStore(repository, users, cache)
            val old = store.observe(30)
            runCurrent()
            every { users.getCurrentUserId() } returns 8L
            assertNull(store.cached(30))
            advanceUntilIdle()
            assertTrue(old.value is ApiResult.Error)
            verify(exactly = 0) { cache.save(any(), any()) }
            assertNull(store.cached(30))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun oneFailedPeriodDoesNotBlockTheOtherPeriodsAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            setup()
            val value = portrait()
            every { repository.getGrowthPortrait(7, day) } returns flowOf(ApiResult.Error(503, "retry"))
            every { repository.getGrowthPortrait(30, day) } returns flowOf(ApiResult.Success(value))
            every { repository.getGrowthPortrait(90, day) } returns flowOf(ApiResult.Success(value))
            val store = GrowthPortraitStore(repository, users, cache)
            store.prefetch(); advanceUntilIdle()
            assertSame(value, store.cached(30))
            assertSame(value, store.cached(90))
            assertNull(store.cached(7))
            every { repository.getGrowthPortrait(7, day) } returns flowOf(ApiResult.Success(value))
            store.observe(7); advanceUntilIdle()
            assertSame(value, store.cached(7))
        } finally { Dispatchers.resetMain() }
    }
}
