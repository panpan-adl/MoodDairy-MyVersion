package com.example.mydiary.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 轻量级离线语音输入封装。
 *
 * 说明：此前使用 Vosk 离线模型，但模型资产未纳入版本控制，
 * 这里回退到 Android 原生 SpeechRecognizer，保持 API 不变。
 */
internal object ChatVosk {

    private var recognizer: SpeechRecognizer? = null

    fun ensureReady(context: Context, callback: (Boolean) -> Unit) {
        callback(SpeechRecognizer.isRecognitionAvailable(context))
    }

    fun startListening(
        appContext: Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("当前设备不支持语音输入")
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                recognizer = null
                onError(mapSpeechRecognizerError(error))
            }
            override fun onResults(results: Bundle?) {
                recognizer = null
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onResult(matches?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话")
        }
        sr.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}

private fun mapSpeechRecognizerError(errorCode: Int): String {
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
