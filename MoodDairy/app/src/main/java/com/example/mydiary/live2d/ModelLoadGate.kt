package com.example.mydiary.live2d

/** Invalidates queued loads and main-thread callbacks after selection or disposal. */
internal class ModelLoadGate {
    private var generation = 0L
    @Volatile var released = false
        private set

    @Synchronized fun next(): Long? = if (released) null else ++generation
    @Synchronized fun accepts(token: Long): Boolean = !released && generation == token
    @Synchronized fun release(): Boolean {
        if (released) return false
        released = true
        generation++
        return true
    }
}
