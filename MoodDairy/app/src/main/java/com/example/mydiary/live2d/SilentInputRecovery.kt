package com.example.mydiary.live2d

/** Exact digital silence differs from quiet microphone noise. Limit emulator recovery. */
class SilentInputRecovery(private val enabled: Boolean, private val rate: Int = 16000) {
    private var zeros = 0L
    private var live = 0L
    var attempts = 0
        private set

    fun sample(samples: ShortArray, count: Int, standby: Boolean): Boolean {
        if (!enabled || !standby) { zeros = 0; return false }
        var nonzero = 0
        for (index in 0 until count) {
            if (samples[index] == 0.toShort()) zeros++
            else { zeros = 0; nonzero++ }
        }
        // Sustained real input opens a new recovery budget; a single spike does not.
        if (nonzero > count / 2) {
            live += count
            if (live >= rate) attempts = 0
        } else live = 0
        if (zeros < rate * 10L || attempts >= 2) return false
        attempts++
        zeros = 0
        live = 0
        return true
    }

    fun resetWindow() { zeros = 0; live = 0 }
}
