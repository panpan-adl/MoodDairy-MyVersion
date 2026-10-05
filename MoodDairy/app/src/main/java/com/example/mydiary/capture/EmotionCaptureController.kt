package com.example.mydiary.capture

import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 情绪采集控制器（骨架实现，待补充）
 * 用于摄像头表情识别采集
 */
@Singleton
class EmotionCaptureController @Inject constructor() {

    /**
     * 开始采集
     * @param lifecycleOwner 生命周期所有者
     * @param callback 回调：情绪标签、置信度
     * @return 是否成功启动
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        callback: (label: String, confidence: Float) -> Unit
    ): Boolean {
        // TODO: 实现摄像头表情采集逻辑
        return false
    }

    /**
     * 停止采集
     */
    fun stop() {
        // TODO: 实现停止采集逻辑
    }
}
