package com.example.mydiary.ui.relax

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class RelaxEngineEvent(val playing: Boolean, val loading: Boolean = false, val error: String? = null)
interface RelaxAudioEngine {
    val positionMs: Long
    val durationMs: Long
    fun load(url: String, loop: Boolean)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setVolume(volume: Float)
    fun release()
}
class RelaxEngineFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun resourceUri(id: Int) = "android.resource://${context.packageName}/$id"
    fun create(onEvent: (RelaxEngineEvent) -> Unit): RelaxAudioEngine {
        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            volume = .5f
            setPlaybackSpeed(1f)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    onEvent(RelaxEngineEvent(player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
                        if (player.playerError != null) "播放失败，请重试或换一个声音。" else null))
                }
            })
        }
        return object : RelaxAudioEngine {
            override val positionMs get() = player.currentPosition.coerceAtLeast(0)
            override val durationMs get() = player.duration.coerceAtLeast(0)
            override fun load(url: String, loop: Boolean) {
                player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
            }
            override fun play() { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
            override fun pause() = player.pause()
            override fun seekTo(ms: Long) = player.seekTo(ms)
            override fun setVolume(volume: Float) { player.volume = volume }
            override fun release() = player.release()
        }
    }
}
