package com.example.mydiary.whitenoise

import android.content.Context
import com.example.mydiary.ui.relax.RelaxPlaybackController

/** Compatibility facade: assistant actions share the same single audio session. */
class WhiteNoiseManager private constructor() {
    companion object {
        private val instance = WhiteNoiseManager()
        fun getInstance() = instance
    }
    private var controller: RelaxPlaybackController? = null
    private val volumes = mutableMapOf<String, Float>()
    fun bind(controller: RelaxPlaybackController) { this.controller = controller }
    fun playSound(@Suppress("UNUSED_PARAMETER") context: Context, sound: WhiteNoiseSound) {
        controller?.playNoise(sound)
        controller?.setVolume(volumes[sound.id] ?: .5f)
    }
    fun pauseSound(soundId: String) { if (controller?.state?.value?.trackId == soundId) controller?.pause() }
    fun stopAllSounds() { if (controller?.state?.value?.whiteNoise == true) controller?.stop() }
    fun isPlaying(soundId: String) = controller?.state?.value?.let { it.whiteNoise && it.trackId == soundId && it.playing } == true
    fun setVolume(soundId: String, volume: Float) {
        volumes[soundId] = volume.coerceIn(0f, 1f)
        if (controller?.state?.value?.trackId == soundId) controller?.setVolume(volume)
    }
    fun getVolume(soundId: String) = volumes[soundId] ?: .5f
    fun release() = stopAllSounds()
    fun setSpeechActive(active: Boolean) { controller?.setSpeechActive(active) }
}
