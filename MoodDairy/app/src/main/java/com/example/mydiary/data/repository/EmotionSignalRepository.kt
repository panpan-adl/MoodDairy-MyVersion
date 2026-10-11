package com.example.mydiary.data.repository

import android.util.Log
import com.example.mydiary.data.models.FaceEmotionReportRequest
import com.example.mydiary.data.models.FaceEmotionReportResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import com.example.mydiary.ml.EmotionModelConfig
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 表情信号数据仓库
 *
 * 职责：
 * - 上报摄像头表情识别结果（POST /face-emotion/report）
 * - 去重：同标签且距上次上报 < 60s 时跳过
 * - 失败重试：指数退避最多 3 次后丢弃
 */
@Singleton
class EmotionSignalRepository @Inject constructor(
    private val apiService: DiaryApiService,
) {

    private val tag = "EmotionSignalRepository"

    private val mutex = Mutex()
    private var lastReportedLabel: String? = null
    private var lastReportedAtMs: Long = 0L
    private var lastPromptAtMs: Long? = null

    /**
     * 上报一条平滑后的表情标签
     *
     * @param label 表情标签
     * @param confidence 置信度
     * 持续负面表情的首次提示绕过普通上报去重，提示成功后冷却五分钟。
     * @return 成功响应（可包含主动建议）；去重跳过或请求失败返回 null。
     */
    suspend fun report(label: String, confidence: Float, sustainedNegativeMs: Long = 0L, facialExpression: String? = null): FaceEmotionReportResponse? {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val promptDue = sustainedNegativeMs >= EmotionModelConfig.NEGATIVE_PROMPT_DURATION_MS &&
                (lastPromptAtMs == null || now - lastPromptAtMs!! >= EmotionModelConfig.PROMPT_COOLDOWN_MS)
            // 模型输出的 7 类与后端枚举一致；任何意外标签兜底为 neutral，避免 422
            val backendLabel = if (label in BACKEND_EMOTIONS) label else "neutral"
            if (backendLabel != label) {
                Log.w(tag, "未知表情标签兜底: $label -> neutral")
            }
            val reportKey = "$backendLabel:${facialExpression.orEmpty()}"
            if (!promptDue && reportKey == lastReportedLabel &&
                now - lastReportedAtMs < EmotionModelConfig.REPORT_MIN_INTERVAL_MS
            ) {
                Log.d(tag, "标签未变化且距上次上报不足 60s，跳过: $backendLabel")
                return null
            }

            val request = FaceEmotionReportRequest(
                emotion = backendLabel,
                confidence = confidence,
                capturedAt = Instant.ofEpochMilli(now).toString(),
                sustainedNegativeMs = if (promptDue) sustainedNegativeMs else 0L,
                facialExpression = facialExpression,
            )

            var backoffMs = INITIAL_BACKOFF_MS
            repeat(MAX_RETRY) { attempt ->
                val result = SafeApiCall.execute { apiService.reportFaceEmotion(request) }
                if (result is ApiResult.Success) {
                    if (!result.data.success) return null
                    lastReportedLabel = reportKey
                    lastReportedAtMs = now
                    Log.d(tag, "表情上报成功: $backendLabel (${result.data.success})")
                    if (result.data.serviceBundle != null) lastPromptAtMs = now
                    return result.data
                }

                if (attempt < MAX_RETRY - 1) {
                    Log.d(tag, "表情上报失败，${backoffMs}ms 后重试: $backendLabel")
                    delay(backoffMs)
                    backoffMs *= 2
                }
            }

            Log.w(tag, "表情上报重试 $MAX_RETRY 次仍失败，丢弃: $backendLabel")
            return null
        }
    }

    companion object {
        private const val MAX_RETRY = 3
        private const val INITIAL_BACKOFF_MS = 1000L

        /** 后端 /face-emotion/report 接受的 7 类表情（与模型输出一致） */
        private val BACKEND_EMOTIONS = setOf(
            "angry", "disgust", "fear", "happy", "sad", "surprise", "neutral",
        )
    }
}
