package com.example.mydiary.data.repository

import com.example.mydiary.data.local.GrowthPortraitCache
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Shared work survives navigation; opening a portrait joins the existing request. */
@Singleton
class GrowthPortraitStore @Inject constructor(
    private val repository: GrowthPortraitRepository,
    private val users: UserRepository,
    private val cache: GrowthPortraitCache,
) {
    private data class Key(val userId: Long, val days: Int, val date: String)
    private class Entry(initial: GrowthPortraitResponse?) {
        val state = MutableStateFlow<ApiResult<GrowthPortraitResponse>>(initial?.let { ApiResult.Success(it) } ?: ApiResult.Loading)
        var savedAt: Long? = null
        var job: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = mutableMapOf<Key, Entry>()
    private fun key(days: Int): Key? = users.getCurrentUserId()?.let { Key(it, days, LocalDate.now().toString()) }
    private fun entry(key: Key) = entries.getOrPut(key) {
        Entry(cache.load(key.userId, key.days, key.date))
    }

    fun cached(days: Int): GrowthPortraitResponse? = key(days)?.let {
        (entry(it).state.value as? ApiResult.Success)?.data
    }

    fun prefetch() {
        listOf(30, 7, 90).forEach { observe(it) }
    }

    fun observe(days: Int): StateFlow<ApiResult<GrowthPortraitResponse>> {
        val key = key(days) ?: return MutableStateFlow(ApiResult.Error(401, "请先登录后查看成长画像"))
        val entry = entry(key)
        val fresh = entry.savedAt?.let { System.nanoTime() - it < 60_000_000_000L } ?: false
        if (entry.job?.isActive != true && !fresh) {
            entry.job = scope.launch {
                try {
                    val result = repository.getGrowthPortrait(key.days, key.date).first { it !is ApiResult.Loading }
                    if (users.getCurrentUserId() != key.userId) {
                        entries.remove(key)
                        entry.state.value = ApiResult.Error(401, "登录状态已变化，请重新进入")
                        return@launch
                    }
                    when (result) {
                        is ApiResult.Success -> {
                            if (result.data.userId != key.userId || result.data.rangeEnd != key.date) {
                                if (entry.state.value !is ApiResult.Success) entry.state.value = ApiResult.Error(-1, "成长画像暂时没有准备好，请重试")
                            } else {
                                entry.state.value = result
                                entry.savedAt = System.nanoTime()
                                runCatching { cache.save(key.days, result.data) }
                            }
                        }
                        is ApiResult.Error -> if (result.code == 401 || result.code == 403 || entry.state.value !is ApiResult.Success) {
                            entry.state.value = result
                        }
                        ApiResult.Loading -> Unit
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (entry.state.value !is ApiResult.Success) entry.state.value = ApiResult.Error(-1, "成长画像暂时没有加载出来，请重试")
                }
            }
        }
        return entry.state.asStateFlow()
    }
}
