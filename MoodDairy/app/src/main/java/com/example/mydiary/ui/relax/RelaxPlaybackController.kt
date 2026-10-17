package com.example.mydiary.ui.relax

import com.example.mydiary.data.models.RelaxTrack
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.whitenoise.WhiteNoiseManager
import com.example.mydiary.whitenoise.WhiteNoiseSound
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class RelaxPlaybackState(
    val trackId: String? = null, val title: String = "", val artist: String = "",
    val whiteNoise: Boolean = false, val loading: Boolean = false, val playing: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null,
    val volume: Float = .5f, val assistantRequest: Long? = null,
)

/** One application-owned session; page removal never owns audio lifetime. */
@Singleton
class RelaxPlaybackController @Inject constructor(
    private val api: DiaryApiService, private val factory: RelaxEngineFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RelaxPlaybackState())
    val state = _state.asStateFlow()
    private var engine: RelaxAudioEngine? = null
    private var request: Job? = null
    private var serial = 0L
    private var pendingNavigation = false
    private var ducked = false
    private var track: RelaxTrack? = null
    private var sound: WhiteNoiseSound? = null
    private var progress: Job? = null
    init { WhiteNoiseManager.getInstance().bind(this) }

    private fun begin(id: String, title: String, artist: String, white: Boolean): Long {
        serial++
        request?.cancel(); request = null; progress?.cancel()
        pendingNavigation = true
        engine?.release(); engine = null
        _state.value = RelaxPlaybackState(trackId = id, title = title, artist = artist,
            whiteNoise = white, loading = true, volume = _state.value.volume)
        return serial
    }

    private fun start(token: Long, url: String, loop: Boolean) {
        if (token != serial) return
        val next = factory.create { event ->
            if (token != serial) return@create
            _state.update { current -> current.copy(playing = event.playing, loading = event.loading,
                error = event.error,
                assistantRequest = if (event.playing && pendingNavigation) token else current.assistantRequest) }
            if (event.playing || event.error != null) pendingNavigation = false
        }
        engine = next
        next.setVolume(effectiveVolume())
        next.load(url, loop)
        progress = scope.launch {
            while (isActive && token == serial) {
                _state.update { it.copy(positionMs = next.positionMs, durationMs = next.durationMs) }
                delay(500)
            }
        }
    }

    fun toggle(track: RelaxTrack) {
        if (!_state.value.whiteNoise && _state.value.trackId == track.id && engine != null && _state.value.error == null) {
            togglePause(returnToAssistant = true); return
        }
        this.track = track; sound = null
        val token = begin(track.id, track.title, track.artist, false)
        request = scope.launch {
            try {
                val response = api.relaxPlayback(track.id)
                if (token != serial) return@launch
                val url = response.body()?.url
                if (!response.isSuccessful || url == null || !url.startsWith("https://"))
                    throw IllegalStateException("歌曲暂时无法播放")
                start(token, url, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == serial) {
                    pendingNavigation = false
                    _state.update { it.copy(loading = false, playing = false, error = "这首歌暂时无法播放，请换一首或重试。") }
                }
            }
        }
    }

    fun playNoise(sound: WhiteNoiseSound) {
        this.sound = sound; track = null
        val token = begin(sound.id, sound.name, "循环环境声", true)
        try { start(token, factory.resourceUri(sound.resourceId), true) }
        catch (_: Exception) { pendingNavigation = false; _state.update { it.copy(loading = false, error = "声音暂时无法播放，请重试。") } }
    }

    fun consumeAssistantRequest(token: Long): Boolean {
        if (_state.value.assistantRequest != token) return false
        _state.update { it.copy(assistantRequest = null) }
        return true
    }

    fun togglePause(returnToAssistant: Boolean = false) {
        if (_state.value.playing || _state.value.loading) { pause(); return }
        if (_state.value.error != null || engine == null) {
            sound?.let { playNoise(it) } ?: track?.let { toggle(it) }; return
        }
        pendingNavigation = returnToAssistant
        engine?.play()
    }

    fun pause() {
        request?.cancel(); request = null
        pendingNavigation = false
        if (_state.value.loading && engine == null) serial++
        engine?.pause()
        _state.update { it.copy(playing = false, loading = false, assistantRequest = null) }
    }
    fun stop() {
        serial++; request?.cancel(); request = null; progress?.cancel(); progress = null; pendingNavigation = false
        engine?.release(); engine = null; track = null; sound = null
        _state.value = RelaxPlaybackState(volume = _state.value.volume)
    }
    fun seekTo(ms: Long) { engine?.seekTo(ms.coerceIn(0, _state.value.durationMs.coerceAtLeast(0))) }
    fun setVolume(volume: Float) { _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }; engine?.setVolume(effectiveVolume()) }
    fun setSpeechActive(active: Boolean) { ducked = active; engine?.setVolume(effectiveVolume()) }
    private fun effectiveVolume() = _state.value.volume * if (ducked) .2f else 1f
}
