package com.example.mydiary.live2d

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.mydiary.data.network.ApiEndpoints
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** One recorder/worker for both offline wake detection and utterance capture. */
class ForegroundVoiceInput(
    private val context: Context,
    client: OkHttpClient,
    private val callback: SherpaAsrEngine.Callback,
    private val onWake: () -> Unit,
    private val onIdle: () -> Unit,
    private val allowed: () -> Boolean,
) {
    private enum class Mode { STOPPED, WAKE, CAPTURE, PROCESSING }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "ranran-microphone").apply { isDaemon = true } }
    private val http = client.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(VoiceUploadRecoveryInterceptor()).build()
    private val callbackGate = VoiceCallbackGate()
    private val generation get() = callbackGate.current()
    @Volatile private var mode = Mode.STOPPED
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var finishRequested = false
    @Volatile private var released = false
    private var call: Call? = null
    private var spotter: LocalWakeSpotter? = null
    private var wakeFailed = false
    private val emulatorInput = android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" }
    private val silentRecovery = SilentInputRecovery(emulatorInput)

    fun standby() { if (!wakeFailed) begin(Mode.WAKE) }
    fun start(): Boolean = begin(Mode.CAPTURE)
    fun finish() { if (mode == Mode.CAPTURE) finishRequested = true }
    fun resetWakeFailure() { wakeFailed = false }

    private fun begin(target: Mode): Boolean {
        if (released || !allowed()) return false
        if (mode == target) return true
        stop()
        mode = target
        val token = generation
        worker.execute { capture(target, token) }
        return true
    }

    private fun post(token: Long, action: () -> Unit) {
        main.post { if (!released && callbackGate.accepts(token, allowed())) action() }
    }

    @Suppress("MissingPermission")
    private fun capture(target: Mode, token: Long) {
        var record: AudioRecord? = null
        var pcm: ByteArray? = null
        var reopenSilentInput = false
        try {
            if (token != generation || released || !allowed()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException()
            if (target == Mode.WAKE) {
                try {
                    if (spotter == null) spotter = LocalWakeSpotter(context)
                    spotter?.reset()
                    Log.i("RanranWake", "Local keyword detector ready")
                } catch (error: Throwable) {
                    if (error !is Exception && error !is LinkageError) throw error
                    wakeFailed = true
                    throw IllegalStateException("本地唤醒加载失败，仍可点击然然说话", error)
                }
            }
            if (token != generation || released || !allowed()) return
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            // The emulator's raw MIC path worked in real wake trials. Use it on
            // every process start rather than relearning the same fallback.
            val source = if (emulatorInput)
                MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = AudioRecord(source, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
            check(record.state == AudioRecord.STATE_INITIALIZED)
            recorder = record
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            silentRecovery.resetWindow()
            Log.i("RanranWake", "Microphone mode=$target source=$source")
            val samples = ShortArray(320)
            var window = VoiceCaptureWindow(SystemClock.elapsedRealtime())
            val audio = ByteArrayOutputStream()
            var firstSpeech = false
            var capturedSamples = 0
            var meterStarted = SystemClock.elapsedRealtime()
            var meterSamples = 0L
            var meterEnergy = 0.0
            var meterPeak = 0
            var silentSamples = 0L
            var missingInputHint = false
            fun write(block: ShortArray, count: Int) {
                for (i in 0 until count) { val value = block[i].toInt(); audio.write(value and 255); audio.write((value shr 8) and 255) }
            }
            while (token == generation && !released && allowed()) {
                val count = record.read(samples, 0, samples.size)
                if (count < 0) throw IllegalStateException("麦克风读取失败")
                if (count == 0) continue
                val now = SystemClock.elapsedRealtime()
                if (silentRecovery.sample(samples, count, mode == Mode.WAKE)) {
                    Log.w("RanranWake", "Reopening silent emulator input attempt=${silentRecovery.attempts}")
                    reopenSilentInput = true
                    break
                }
                // Log signal levels only, never speech or standby audio. A running
                // recorder does not guarantee the emulator forwards host input.
                for (i in 0 until count) {
                    val value = samples[i].toInt()
                    meterPeak = maxOf(meterPeak, abs(value))
                    meterEnergy += value.toDouble() * value
                    silentSamples = if (value == 0) silentSamples + 1 else 0
                }
                meterSamples += count
                if (now - meterStarted >= 5000) {
                    Log.i("RanranWake", "Input mode=$mode samples=$meterSamples peak=$meterPeak rms=${sqrt(meterEnergy / meterSamples).toInt()}")
                    meterStarted = now
                    meterSamples = 0
                    meterEnergy = 0.0
                    meterPeak = 0
                }
                if (mode == Mode.WAKE && silentSamples >= 16000 * 10 && !missingInputHint) {
                    missingInputHint = true
                    Log.w("RanranWake", "No microphone signal for 10 seconds; check input routing")
                    post(token) {
                        if (mode == Mode.WAKE) VoiceWakeSettings.updateStatus("暂未收到声音，请检查麦克风是否开启")
                    }
                } else if (missingInputHint && silentSamples == 0L) {
                    missingInputHint = false
                    post(token) {
                        if (mode == Mode.WAKE) VoiceWakeSettings.updateStatus(VoiceWakeSettings.WAITING)
                    }
                }
                if (mode == Mode.WAKE) {
                    if (spotter?.accept(samples, count) == true) {
                        Log.i("RanranWake", "Wake word detected")
                        mode = Mode.CAPTURE
                        window = VoiceCaptureWindow(now)
                        post(token, onWake)
                        // Start this capture block immediately, retaining speech following
                        // the keyword without uploading buffered standby microphone audio.
                    } else continue
                }
                val voiced = (0 until count).count { abs(samples[it].toInt()) > 700 } > count / 20
                if (voiced && !firstSpeech) firstSpeech = true
                if (firstSpeech) { write(samples, count); capturedSamples += count }
                val outcome = window.sample(now, voiced)
                if (outcome == VoiceCaptureWindow.Outcome.IDLE || (finishRequested && !firstSpeech)) {
                    mode = Mode.STOPPED
                    post(token, onIdle)
                    return
                }
                if (outcome == VoiceCaptureWindow.Outcome.SUBMIT || finishRequested || capturedSamples >= 16000 * 15) {
                    if (firstSpeech) pcm = audio.toByteArray()
                    break
                }
            }
        } catch (error: Exception) {
            if (token == generation && !released) {
                mode = Mode.STOPPED
                post(token) { callback.onError(if (error is SecurityException) "请允许麦克风权限后使用语音"
                    else error.message?.takeIf { it.startsWith("本地唤醒") } ?: "麦克风暂不可用，请检查是否被其他应用占用") }
            }
        } finally {
            if (recorder === record) recorder = null
            runCatching { record?.stop() }
            runCatching { record?.release() }
            Log.i("RanranWake", "Microphone released")
            if (token == generation && pcm == null) mode = Mode.STOPPED
        }
        if (token != generation || released || !allowed()) return
        if (reopenSilentInput) {
            post(token) {
                VoiceWakeSettings.updateStatus("正在恢复麦克风连接…")
                main.postDelayed({
                    if (!released && callbackGate.accepts(token, allowed()) && mode == Mode.STOPPED) begin(Mode.WAKE)
                }, 200)
            }
            return
        }
        val data = pcm ?: return
        mode = Mode.PROCESSING
        post(token) { upload(data, token) }
    }

    private fun upload(pcm: ByteArray, token: Long) {
        callback.onProcessing()
        val began = SystemClock.elapsedRealtime()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "voice.pcm", pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        val request = Request.Builder().url(ApiEndpoints.apiBaseUrl + "voice/companion-transcribe").post(body).build()
        call = http.newCall(request).also { pending ->
            pending.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("CompanionLatency", "ASR transport failed: ${e.javaClass.simpleName}")
                    complete(null, VoiceRecognitionErrors.connection(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = try { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
                        catch (error: IOException) { onFailure(call, error); return }
                        val text = if (it.isSuccessful) runCatching {
                            val value = JsonParser.parseString(raw).asJsonObject["text"]
                            if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString)
                                value.asString.trim() else null
                        }.getOrNull() else null
                        complete(text, if (it.isSuccessful && text == null)
                            "语音识别结果异常，请再试一次" else VoiceRecognitionErrors.response(it.code))
                    }
                }
                private fun complete(text: String?, error: String) {
                    post(token) {
                        call = null
                        mode = Mode.STOPPED
                        Log.i("CompanionLatency", "recognition_ms=${SystemClock.elapsedRealtime()-began}")
                        if (!text.isNullOrBlank()) callback.onSegment(text)
                        else if (text != null) onIdle()
                        else callback.onError(error)
                    }
                }
            })
        }
    }

    fun stop() {
        callbackGate.invalidate()
        mode = Mode.STOPPED
        finishRequested = false
        call?.cancel(); call = null
        runCatching { recorder?.stop() } // Unblock read; the worker owns release.
    }
    fun release() {
        stop()
        released = true
        worker.execute { spotter?.release(); spotter = null }
        worker.shutdown()
    }
}
