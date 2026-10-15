package com.example.mydiary.live2d

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 sherpa-onnx + SenseVoice 的高精度离线中文语音识别引擎。
 *
 * 使用 Silero VAD 从连续音频中自动切分语音段，每段交给 SenseVoice 识别。
 * 识别准确率远高于 Vosk small 模型，且自带标点与逆文本归一化。
 */
class SherpaAsrEngine(private val context: Context) {

    interface Callback {
        /** 模型加载完毕，可以开始听了 */
        fun onReady()
        /** VAD 切出的一段语音被识别成文字 */
        fun onSegment(text: String)
        /** 引擎初始化或运行中出错 */
        fun onError(message: String)
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private val running = AtomicBoolean(false)

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var callback: Callback? = null

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    /** 加载 VAD 和 SenseVoice 模型（只需调用一次，重复调用会忽略） */
    fun prepare() {
        if (recognizer != null) {
            callback?.onReady()
            return
        }
        try {
            val assetManager = context.assets
            val modelDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(assetManager, vadConfig)

            val recogConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = "zh",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "sense_voice",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            recognizer = OfflineRecognizer(assetManager, recogConfig)
            Log.i(TAG, "Sherpa ASR 引擎就绪")
            callback?.onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa ASR 初始化失败", e)
            callback?.onError("语音识别引擎初始化失败：${e.message}")
        }
    }

    /** 开始连续监听（VAD 会自动把说话切成一段一段） */
    fun start() {
        if (running.get()) return
        if (recognizer == null || vad == null) {
            prepare()
            if (recognizer == null) return
        }
        running.set(true)
        job = scope.launch { recordLoop() }
        Log.i(TAG, "Sherpa ASR 开始监听")
    }

    /** 停止监听 */
    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioRecord = null
        Log.i(TAG, "Sherpa ASR 停止监听")
    }

    /** 彻底释放模型和录音资源 */
    fun release() {
        stop()
        vad?.release()
        recognizer?.release()
        vad = null
        recognizer = null
    }

    private suspend fun recordLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = record

        val vad = vad ?: return
        val recognizer = recognizer ?: return

        record.startRecording()

        val windowSize = 512
        val buffer = ShortArray(windowSize)
        val floatBuffer = FloatArray(windowSize)

        try {
            while (running.get()) {
                val read = record.read(buffer, 0, windowSize)
                if (read <= 0) continue

                for (i in 0 until read) {
                    floatBuffer[i] = buffer[i] / 32768.0f
                }
                vad.acceptWaveform(floatBuffer.copyOf(read))

                while (!vad.empty()) {
                    val segment = vad.front()
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    stream.release()
                    vad.pop()

                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            callback?.onSegment(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "录音循环出错", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("录音出错：${e.message}")
                }
            }
        } finally {
            try {
                record.stop()
                record.release()
            } catch (_: Exception) {}
            audioRecord = null
        }
    }

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16000
    }
}
