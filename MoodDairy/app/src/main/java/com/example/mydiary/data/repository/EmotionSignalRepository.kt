package com.example.mydiary.data.repository

import com.example.mydiary.data.network.DiaryApiService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 情绪信号上报仓库（骨架实现，待补充）
 * 将摄像头识别到的情绪信号上报给后端
 */
@Singleton
class EmotionSignalRepository @Inject constructor(
    private val apiService: DiaryApiService
) {

    /**
     * 上报情绪信号
     * @param label 情绪标签
     * @param confidence 置信度
     */
    suspend fun report(label: String, confidence: Float) {
        // TODO: 调用 apiService 上报情绪数据
    }
}
