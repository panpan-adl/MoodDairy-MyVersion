package com.example.mydiary.ml

/**
 * 表情识别结果平滑器
 *
 * 策略：
 * 1. 置信度低于 [EmotionModelConfig.CONFIDENCE_THRESHOLD] 的样本直接丢弃
 * 2. 滑动窗口（默认容量 3）内多数投票
 * 3. 滞后防抖：新标签必须取得窗口多数票（> 一半）才切换，否则维持当前稳定标签
 *
 * 纯 Kotlin 实现，可在 JVM 单元测试中直接调用。
 */
class EmotionSmoother(
    private val windowSize: Int = 3,
    private val confidenceThreshold: Float = EmotionModelConfig.CONFIDENCE_THRESHOLD,
) {

    private val window = ArrayDeque<Pair<String, Float>>(windowSize)
    private var stablePrediction: Pair<String, Float>? = null

    /**
     * 添加一帧识别样本
     *
     * @param label 表情标签
     * @param confidence 置信度 (0~1)
     * @return 平滑后的稳定标签；置信度不足或窗口未稳定时返回 null
     */
    fun addSample(label: String, confidence: Float): String? {
        return addPrediction(label, confidence)?.first
    }

    fun reset() {
        window.clear()
        stablePrediction = null
    }

    fun addPrediction(label: String, confidence: Float): Pair<String, Float>? {
        // 置信度不足的样本不进入窗口
        if (!confidence.isFinite() || confidence !in 0f..1f || confidence < confidenceThreshold) {
            return null
        }

        if (window.size >= windowSize) {
            window.removeFirst()
        }
        window.addLast(label to confidence)

        // 窗口未填满，尚未稳定
        if (window.size < windowSize) {
            return null
        }

        val (topLabel, topCount) = window
            .groupingBy { it.first }
            .eachCount()
            .maxByOrNull { it.value }
            ?: return null

        // 滞后防抖：只有取得多数票才更新稳定标签
        if (topCount > windowSize / 2) {
            val matching = window.filter { it.first == topLabel }
            stablePrediction = topLabel to matching.map { it.second }.average().toFloat()
        }
        return stablePrediction
    }
}
