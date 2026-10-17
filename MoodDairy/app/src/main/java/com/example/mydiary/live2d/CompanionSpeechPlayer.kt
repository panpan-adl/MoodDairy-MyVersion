package com.example.mydiary.live2d

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.mydiary.data.network.ApiEndpoints
import java.net.URLEncoder
import java.util.ArrayDeque

/** Main-thread ordered playback, with the next sentence preparing during playback. */
class CompanionSpeechPlayer(private val onBusy: (Boolean) -> Unit,
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
    private val fallback: (String, () -> Unit) -> Unit = { _, done -> done() }) {
    private class Clip(val text: String, var player: MediaPlayer? = null,
        var ready: Boolean = false, var started: Boolean = false, var failed: Boolean = false)
    private val clips = ArrayDeque<Clip>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeouts = mutableMapOf<Clip, Runnable>()
    fun enqueue(text: String) {
        if (text.isBlank()) return
        clips.add(Clip(text.take(500)))
        onBusy(true)
        pump()
    }
    fun clear(notify: Boolean = true) {
        clips.forEach { it.player?.let { player -> runCatching { player.release() } } }
        clips.clear()
        timeouts.values.forEach(handler::removeCallbacks)
        timeouts.clear()
        if (notify) onBusy(false)
    }
    private fun remove(clip: Clip) {
        if (!clips.remove(clip)) return
        timeouts.remove(clip)?.let(handler::removeCallbacks)
        clip.player?.let { runCatching { it.release() } }
        pump()
    }
    private fun pump() {
        if (clips.isEmpty()) { onBusy(false); return }
        // At most two synthesis requests: playing sentence and one ahead.
        clips.take(2).forEach { clip ->
            if (clip.player == null && !clip.failed) {
                val began = SystemClock.elapsedRealtime()
                try {
                    val player = playerFactory()
                    clip.player = player
                    player.setDataSource(ApiEndpoints.apiBaseUrl + "tts?text=" + URLEncoder.encode(clip.text, "UTF-8"))
                    player.setOnPreparedListener {
                        if (clips.contains(clip)) {
                            timeouts.remove(clip)?.let(handler::removeCallbacks)
                            clip.ready = true
                            Log.i("CompanionLatency", "tts_prepare_ms=${SystemClock.elapsedRealtime()-began}")
                            pump()
                        }
                    }
                    player.setOnCompletionListener { remove(clip) }
                    player.setOnErrorListener { _, _, _ -> fail(clip); true }
                    val timeout = Runnable { fail(clip) }
                    timeouts[clip] = timeout
                    handler.postDelayed(timeout, 30_000)
                    player.prepareAsync()
                } catch (_: Exception) { fail(clip) }
            }
        }
        clips.peek()?.let { first ->
            if (first.failed && !first.started) {
                first.started = true
                fallback(first.text) { remove(first) }
            } else if (first.ready && !first.started) {
                first.started = true
                runCatching { first.player?.start() }.onFailure { first.started = false; fail(first) }
            }
        }
    }
    private fun fail(clip: Clip) {
        if (!clips.contains(clip)) return
        timeouts.remove(clip)?.let(handler::removeCallbacks)
        clip.player?.let { runCatching { it.release() } }
        clip.player = null
        clip.failed = true
        clip.ready = false
        pump()
    }
}
