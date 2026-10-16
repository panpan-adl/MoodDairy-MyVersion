package com.example.mydiary.live2d

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        fun onSegment(text: String)
        fun onError(message: String)
    }

    private var callback: Callback? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

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
    fun start() {
        if (listening) return
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onError("当前设备不支持语音识别")
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                listening = false
                recognizer?.destroy()
                recognizer = null
                callback?.onError(mapError(error))
            }
            override fun onResults(results: Bundle?) {
                listening = false
                recognizer?.destroy()
                recognizer = null
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                callback?.onSegment(matches?.firstOrNull().orEmpty())
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
        sr.startListening(intent)
        listening = true
    }

    /** 停止聆听 */
    fun stop() {
        listening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
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
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时，请按住后直接说话"
            else -> "语音输入失败（$errorCode）"
        }
    }
}
