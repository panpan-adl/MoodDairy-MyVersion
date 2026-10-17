package com.example.mydiary.ui.relax

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.mydiary.data.models.RelaxTrack
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.whitenoise.WhiteNoiseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class RelaxPlaybackState(
    val trackId: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

@HiltViewModel
class RelaxPlayerViewModel @Inject constructor(
    private val api: DiaryApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(RelaxPlaybackState())
    val state = _state.asStateFlow()
    private var player: ExoPlayer? = null
    private var request: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                player?.let { current ->
                    _state.update { it.copy(positionMs = current.currentPosition.coerceAtLeast(0),
                        durationMs = current.duration.coerceAtLeast(0)) }
                }
                delay(500)
            }
        }
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { current ->
        current.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        current.setHandleAudioBecomingNoisy(true)
        current.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(loading = playbackState == Player.STATE_BUFFERING) }
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(loading = false, playing = false,
                    error = "播放失败，请重试或换一首歌。") }
            }
        })
        player = current
    }

    fun toggle(track: RelaxTrack) {
        if (_state.value.trackId == track.id && _state.value.error == null && player?.currentMediaItem != null) {
            if (player?.playWhenReady == true) pause() else {
                WhiteNoiseManager.getInstance().stopAllSounds()
                if (player?.playbackState == Player.STATE_ENDED) player?.seekTo(0)
                player?.play()
            }
            return
        }
        request?.cancel()
        player?.stop()
        player?.clearMediaItems()
        _state.value = RelaxPlaybackState(trackId = track.id, loading = true)
        request = viewModelScope.launch {
            try {
                val response = api.relaxPlayback(track.id)
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(response.errorBody()?.string().orEmpty()).optString("detail") }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                    throw IllegalStateException(message ?: "暂时无法获取音频，请稍后重试。")
                }
                val url = response.body()?.url ?: throw IllegalStateException("歌曲音频暂不可用。")
                val uri = Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) throw IllegalStateException("歌曲音频链接不可用。")
                WhiteNoiseManager.getInstance().stopAllSounds()
                player().apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    play()
                }
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                _state.update { it.copy(loading = false, playing = false,
                    error = exc.message ?: "播放失败，请重试或换一首歌。") }
            }
        }
    }

    fun pause() {
        request?.cancel()
        request = null
        player?.pause()
        _state.update { it.copy(loading = false, playing = false) }
    }

    fun stop() {
        pause()
        player?.stop()
        player?.clearMediaItems()
        _state.value = RelaxPlaybackState()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun onCleared() {
        request?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}
