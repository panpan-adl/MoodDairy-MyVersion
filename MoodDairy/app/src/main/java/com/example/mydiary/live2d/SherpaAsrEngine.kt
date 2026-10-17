package com.example.mydiary.live2d

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 语音识别引擎（基于 Android 原生 SpeechRecognizer）。
 *
 * 说明：此前使用 sherpa-onnx + SenseVoice，但该库未纳入版本控制。
 * 这里回退到系统自带的语音识别，保持原有接口不变。
 */
class SherpaAsrEngine(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onProcessing() {}
        fun onSegment(text: String)
        fun onError(message: String)
    }

    private var callback: Callback? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private var resultTimeout: Runnable? = null

    private fun dispose(sr: SpeechRecognizer) {
        if (recognizer !== sr) return
        // Invalidate before destroy: cancelled sessions can still deliver callbacks.
        recognizer = null
        listening = false
        resultTimeout?.let(handler::removeCallbacks)
        resultTimeout = null
        runCatching { sr.destroy() }
    }

    private fun awaitResult(sr: SpeechRecognizer) {
        if (resultTimeout != null) return
        callback?.onProcessing()
        resultTimeout = Runnable {
            if (recognizer === sr) {
                dispose(sr)
                callback?.onError("语音识别服务未返回结果，请检查网络后重试")
            }
        }.also { handler.postDelayed(it, 8000) }
    }

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    /** 准备引擎（异步） */
    fun prepare() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onReady()
        } else {
            callback?.onError("当前设备不支持语音识别")
        }
    }

    /** 开始聆听（手动模式，识别到一段话后自动停止） */
    fun start(): Boolean {
        if (listening) return true
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onError("当前设备不支持语音识别")
            return false
        }
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            callback?.onError("无法启动系统语音识别，请检查设备的语音服务")
            return false
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (recognizer === sr) awaitResult(sr)
            }
            override fun onError(error: Int) {
                if (recognizer !== sr) return
                dispose(sr)
                callback?.onError(mapError(error))
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== sr) return
                dispose(sr)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim().orEmpty()
                if (text.isBlank()) callback?.onError("没有识别到有效语音，请再点然然说一次")
                else callback?.onSegment(text)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        return try {
            sr.startListening(intent)
            true
        } catch (_: SecurityException) {
            dispose(sr)
            callback?.onError("语音输入失败：缺少麦克风权限")
            false
        } catch (_: Exception) {
            dispose(sr)
            callback?.onError("无法启动系统语音识别，请稍后重试")
            false
        }
    }

    /** Finish capture but keep the listener alive for the recognizer's final text. */
    fun finish() {
        val sr = recognizer ?: return
        if (resultTimeout != null) return
        awaitResult(sr)
        try {
            sr.stopListening()
        } catch (_: Exception) {
            dispose(sr)
            callback?.onError("结束录音失败，请再点然然重试")
        }
    }

    /** Cancel a session without submitting it, e.g. when leaving the app. */
    fun stop() {
        val sr = recognizer ?: return
        dispose(sr)
    }

    /** 释放资源 */
    fun release() {
        stop()
    }

    private fun mapError(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "语音输入失败：录音异常"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音输入失败：缺少麦克风权限"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音输入失败：网络异常"
            SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到有效语音，请再说一次"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务异常"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，请点一下然然后直接说话"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "系统语音服务不支持中文，请在设备语音设置中启用中文"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文语音识别数据尚未就绪，请在设备语音设置中下载中文语言包"
            else -> "语音输入失败（$errorCode）"
        }
    }
}
