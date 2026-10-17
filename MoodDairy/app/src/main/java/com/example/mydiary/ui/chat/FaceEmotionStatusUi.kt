package com.example.mydiary.ui.chat

import com.example.mydiary.ml.EmotionModelConfig
import com.example.mydiary.ml.FaceObservationStatus

/** Display-only state; never changes the recognition or reporting decision. */
data class FaceEmotionStatusUi(val icon: String, val label: String, val description: String) {
    companion object {
        val Recognizing = FaceEmotionStatusUi("⏳", "识别中", "正在识别表情")
        val Unavailable = FaceEmotionStatusUi("⚠️", "识别暂不可用", "摄像头或表情模型不可用")

        fun fromObservation(label: String?, confidence: Float): FaceEmotionStatusUi {
            val detail = FaceObservationStatus.message(label)
            if (detail != null) return when (label) {
                null -> FaceEmotionStatusUi("👤", "未检测到人脸", detail)
                "face_too_small" -> FaceEmotionStatusUi("👤", "请靠近一点", detail)
                "face_turned" -> FaceEmotionStatusUi("👤", "请面向屏幕", detail)
                "incomplete_face" -> FaceEmotionStatusUi("👤", "请退后一点", detail)
                "stabilizing_face" -> FaceEmotionStatusUi("⏳", "确认中", detail)
                else -> FaceEmotionStatusUi("⚠️", "识别暂不可用", detail)
            }
            val expression = when (label) {
                "happy" -> "😄" to "开心"
                "smile" -> "🙂" to "微笑"
                "broad_smile" -> "😁" to "明显笑容"
                "delighted_surprise" -> "🤩" to "惊喜"
                "neutral" -> "😐" to "平静"
                "sad" -> "😔" to "难过"
                "angry" -> "😠" to "生气"
                "fear" -> "😨" to "害怕"
                "disgust" -> "😣" to "厌恶"
                "surprise" -> "😮" to "惊讶"
                else -> return Recognizing
            }
            if (!confidence.isFinite() || confidence !in EmotionModelConfig.CONFIDENCE_THRESHOLD..1f) {
                return FaceEmotionStatusUi("⏳", "确认中", "表情置信度不足，正在确认")
            }
            return FaceEmotionStatusUi(expression.first, expression.second, "当前表情：${expression.second}，识别结果仅供参考")
        }
    }
}
