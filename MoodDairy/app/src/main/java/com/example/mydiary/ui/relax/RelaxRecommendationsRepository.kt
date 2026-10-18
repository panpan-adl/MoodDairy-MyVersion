package com.example.mydiary.ui.relax

import android.content.Context
import com.example.mydiary.data.models.RelaxRecommendations
import com.example.mydiary.data.network.DiaryApiService
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Cache recommendation metadata only; playback URLs are always resolved when selected. */
@Singleton
class RelaxRecommendationsRepository @Inject constructor(
    private val api: DiaryApiService,
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("relax_recommendations", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cached = runCatching {
        gson.fromJson(prefs.getString("tracks", null), RelaxRecommendations::class.java)?.tracks
            ?.filter { it.id.isNotBlank() && it.title.isNotBlank() }.orEmpty()
    }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(RelaxMusicState(tracks = cached))
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var fetchedAt = if (cached.isEmpty()) 0L else prefs.getLong("fetched_at", 0L)

    suspend fun refresh(force: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            val age = System.currentTimeMillis() - fetchedAt
            if (!force && _state.value.tracks.isNotEmpty() && age in 0 until 30 * 60 * 1000L) return
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val response = withTimeout(12_000) { api.relaxRecommendations() }
                val tracks = response.body()?.tracks.orEmpty()
                check(response.isSuccessful && tracks.isNotEmpty())
                fetchedAt = System.currentTimeMillis()
                _state.value = RelaxMusicState(tracks = tracks)
                withContext(Dispatchers.IO) {
                    prefs.edit().putString("tracks", gson.toJson(RelaxRecommendations(tracks)))
                        .putLong("fetched_at", fetchedAt).apply()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) showFailure()
                else { _state.value = _state.value.copy(loading = false); throw cancelled }
            } catch (_: Exception) { showFailure() }
        } finally { mutex.unlock() }
    }

    private fun showFailure() {
        _state.value = _state.value.copy(loading = false, error = if (_state.value.tracks.isEmpty())
            "音乐暂时没有加载出来，可以重试，或先听白噪音。"
            else "暂时无法更新，仍可播放上次的音乐。")
    }
}
