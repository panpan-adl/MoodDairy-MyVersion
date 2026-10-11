package com.example.mydiary.ml

/** Technical failures are never presented as a request to reposition the user's face. */
object FaceObservationStatus {
    fun message(label: String?): String? = when (label) {
        null -> "暂未检测到人脸"
        "face_too_small" -> "检测到人脸，请稍靠近摄像头"
        "face_turned" -> "检测到人脸，转动幅度较大，请稍微面向屏幕"
        "incomplete_face" -> "脸部超出画面较多，请稍微退后"
        "inference_failed" -> "检测到人脸，表情分析暂时失败，正在重试"
        "frame_failed" -> "摄像头画面处理失败，正在重试"
        "stabilizing_face" -> "检测到人脸，正在确认表情"
        else -> null
    }
}
