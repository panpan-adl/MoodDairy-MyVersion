package com.example.mydiary.live2d

import android.content.Context
import com.k2fsa.sherpa.onnx.*

/** Called only on the microphone worker. No networking or persistent recordings. */
class LocalWakeSpotter(context: Context) {
    private val spotter = KeywordSpotter(context.assets, KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "voice-wake/encoder.onnx", decoder = "voice-wake/decoder.onnx",
                joiner = "voice-wake/joiner.onnx"),
            tokens = "voice-wake/tokens.txt", numThreads = 1, modelType = "zipformer2"),
        keywordsFile = "voice-wake/keywords.txt", keywordsThreshold = 0.35f))
    private val stream = spotter.createStream()
    fun accept(samples: ShortArray, count: Int): Boolean {
        stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                spotter.reset(stream)
                return true
            }
        }
        return false
    }
    fun reset() = spotter.reset(stream)
    fun release() { stream.release(); spotter.release() }
}
