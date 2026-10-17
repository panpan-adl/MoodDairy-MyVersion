package com.example.mydiary.live2d

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import com.example.mydiary.data.network.ApiEndpoints

/** Explicit tap-to-talk using the app's authenticated ASR, independent of Google speech services. */
class CompanionVoiceInput(
    private val context: Context,
    client: OkHttpClient,
    private val callback: SherpaAsrEngine.Callback,
) {
    private val client = client.newBuilder().readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val healthClient = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var audio: File? = null
    private var call: Call? = null
    private var generation = 0L
    private var silenceCheck: Runnable? = null
    private var heardSpeech = false
    private var peakAmplitude = 0

    private fun sampleAmplitude(recording: MediaRecorder): Boolean {
        val amplitude = runCatching { recording.maxAmplitude }.getOrDefault(0)
        peakAmplitude = maxOf(peakAmplitude, amplitude)
        if (amplitude > 700) heardSpeech = true
        return amplitude > 700
    }

    fun start(): Boolean {
        stop()
        heardSpeech = false
        peakAmplitude = 0
        val current = generation
        return try {
            val file = File.createTempFile("ranran-", ".m4a", context.cacheDir)
            audio = file
            val recording = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = recording
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setAudioSamplingRate(16000)
            recording.setAudioEncodingBitRate(64000)
            recording.setOutputFile(file.absolutePath)
            recording.prepare()
            recording.start()
            val started = SystemClock.uptimeMillis()
            var lastSpeech = started
            silenceCheck = object : Runnable {
                override fun run() {
                    if (current != generation || recorder !== recording) return
                    val now = SystemClock.uptimeMillis()
                    if (sampleAmplitude(recording)) {
                        lastSpeech = now
                    }
                    if (now - started >= 15000 || (heardSpeech && now - lastSpeech >= 1500)) finish()
                    else main.postDelayed(this, 200)
                }
            }.also { main.postDelayed(it, 200) }
            true
        } catch (_: Exception) {
            stop()
            callback.onError("无法录音，请检查麦克风权限或是否被其他应用占用")
            false
        }
    }

    fun finish() {
        val recording = recorder ?: return
        recorder = null
        silenceCheck?.let(main::removeCallbacks)
        silenceCheck = null
        val file = audio ?: return
        sampleAmplitude(recording)
        try { recording.stop() }
        catch (_: Exception) {
            recording.release(); file.delete(); audio = null
            callback.onError("录音太短，请再点然然说一次")
            return
        }
        recording.release()
        if (!heardSpeech) {
            file.delete()
            audio = null
            callback.onError("没有检测到声音，请检查麦克风；使用模拟器时请开启电脑麦克风转发")
            return
        }
        val current = generation
        callback.onProcessing()
        val recognitionStarted = SystemClock.elapsedRealtime()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "voice.m4a", file.asRequestBody("audio/mp4".toMediaType())).build()
        val request = Request.Builder().url(ApiEndpoints.apiBaseUrl + "voice/companion-transcribe").post(body).build()
        call = client.newCall(request).also { pending ->
            pending.enqueue(object : Callback {
                private fun deliver(text: String?, message: String?) {
                    android.util.Log.i("CompanionLatency", "recognition_ms=${SystemClock.elapsedRealtime()-recognitionStarted} success=${!text.isNullOrBlank()}")
                    file.delete()
                    main.post {
                        if (generation != current) return@post
                        audio = null
                        call = null
                        if (!text.isNullOrBlank()) callback.onSegment(text)
                        else callback.onError(message ?: "没有听清，请再点然然说一次")
                    }
                }
                override fun onFailure(call: Call, error: IOException) {
                    if (generation != current) { file.delete(); return }
                    if (error is InterruptedIOException) {
                        deliver(null, "语音识别超时，请稍后重试")
                        return
                    }
                    // Diagnose connectivity without uploading the recording a second time.
                    val probe = healthClient.newCall(Request.Builder()
                        .url(ApiEndpoints.apiBaseUrl + "health").get().build())
                    main.post {
                        if (generation != current) { file.delete(); return@post }
                        this@CompanionVoiceInput.call = probe
                        probe.enqueue(object : Callback {
                            override fun onFailure(call: Call, error: IOException) {
                                deliver(null, "无法连接应用后端，请确认电脑后端已启动且设备连接正常")
                            }
                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    deliver(null, if (it.isSuccessful)
                                        "语音上传连接中断，请重新录音后重试"
                                    else "应用后端暂不可用，请稍后重试")
                                }
                            }
                        })
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = try { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
                        catch (error: IOException) { onFailure(call, error); return }
                        val text = if (it.isSuccessful) runCatching {
                            val value = JsonParser.parseString(raw).asJsonObject.get("text")
                            if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString)
                                value.asString.trim() else null
                        }.getOrNull() else null
                        val error = when (it.code) {
                            401, 403 -> "登录已过期，请重新登录后使用语音"
                            408, 504 -> "语音识别超时，请稍后重试"
                            503 -> "语音识别服务暂不可用，请稍后再试"
                            else -> if (!it.isSuccessful) "语音识别失败，请稍后重试"
                                else if (text == null)
                                    "语音识别服务返回异常，请稍后重试"
                                else null
                        }
                        deliver(text, error)
                    }
                }
            })
        }
    }

    fun stop() {
        generation++
        silenceCheck?.let(main::removeCallbacks)
        silenceCheck = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        call?.cancel()
        call = null
        audio?.delete()
        audio = null
    }
}
