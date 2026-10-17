package com.example.mydiary.ui.chat

import org.junit.Assert.*
import org.junit.Test

class FaceEmotionStatusUiTest {
    @Test fun mapsEveryExpressionWithoutConfidenceText() {
        val expected = mapOf(
            "happy" to ("😄" to "开心"), "smile" to ("🙂" to "微笑"),
            "broad_smile" to ("😁" to "明显笑容"), "delighted_surprise" to ("🤩" to "惊喜"),
            "neutral" to ("😐" to "平静"), "sad" to ("😔" to "难过"),
            "angry" to ("😠" to "生气"), "fear" to ("😨" to "害怕"),
            "disgust" to ("😣" to "厌恶"), "surprise" to ("😮" to "惊讶"),
        )
        expected.forEach { (label, display) ->
            val status = FaceEmotionStatusUi.fromObservation(label, .8f)
            assertEquals(display.first, status.icon)
            assertEquals(display.second, status.label)
            assertTrue(status.description.contains(display.second))
            assertFalse(status.label.contains("%"))
        }
    }

    @Test fun uncertainOrInvalidConfidenceNeverPresentsAnEmotion() {
        for (confidence in listOf(.39f, Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f)) {
            assertEquals("确认中", FaceEmotionStatusUi.fromObservation("sad", confidence).label)
        }
        assertEquals("难过", FaceEmotionStatusUi.fromObservation("sad", .4f).label)
    }

    @Test fun observationFailuresHaveSpecificShortLabelsAndFullDescriptions() {
        val expected = mapOf(null to "未检测到人脸", "face_too_small" to "请靠近一点",
            "face_turned" to "请面向屏幕", "incomplete_face" to "请退后一点",
            "stabilizing_face" to "确认中", "inference_failed" to "识别暂不可用",
            "frame_failed" to "识别暂不可用")
        expected.forEach { (label, text) ->
            val status = FaceEmotionStatusUi.fromObservation(label, 0f)
            assertEquals(text, status.label)
            assertTrue(status.description.isNotBlank())
        }
        assertEquals(FaceEmotionStatusUi.Recognizing, FaceEmotionStatusUi.fromObservation("unknown", .9f))
    }
}
