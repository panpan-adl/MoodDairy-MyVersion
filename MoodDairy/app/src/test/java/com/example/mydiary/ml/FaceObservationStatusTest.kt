package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class FaceObservationStatusTest {
    @Test fun inferenceFailureDoesNotAskUserToReposition() {
        val message = FaceObservationStatus.message("inference_failed")!!
        assertTrue(message.contains("分析暂时失败"))
        assertFalse(message.contains("正对"))
        assertFalse(message.contains("靠近"))
    }
    @Test fun poseDistanceAndPartialFaceHaveDifferentGuidance() {
        assertTrue(FaceObservationStatus.message("face_too_small")!!.contains("靠近"))
        assertTrue(FaceObservationStatus.message("face_turned")!!.contains("转动"))
        assertTrue(FaceObservationStatus.message("incomplete_face")!!.contains("退后"))
        assertNull(FaceObservationStatus.message("sad"))
    }
}
