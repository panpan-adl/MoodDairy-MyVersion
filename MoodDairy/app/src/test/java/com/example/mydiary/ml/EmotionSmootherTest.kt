package com.example.mydiary.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EmotionSmoother 滑动窗口投票 / 置信度门槛 / 滞后防抖 单元测试
 *
 * 生产默认窗口为 3（配合 5s 采集间隔，约 15s 得到首个稳定表情）。
 */
class EmotionSmootherTest {

    @Test fun stableConfidenceBelongsToWinningLabel() {
        val smoother = EmotionSmoother()
        smoother.addSample("happy", 0.6f)
        smoother.addSample("happy", 0.8f)
        val result = smoother.addPrediction("sad", 0.99f)!!
        assertEquals("happy", result.first)
        assertEquals(0.7f, result.second, 0.001f)
    }

    @Test fun missingFaceResetDiscardsOldVotes() {
        val smoother = EmotionSmoother()
        repeat(3) { smoother.addSample("sad", 0.8f) }
        smoother.reset()
        assertNull(smoother.addSample("happy", 0.8f))
        assertNull(smoother.addSample("happy", 0.8f))
        assertEquals("happy", smoother.addSample("happy", 0.8f))
    }

    @Test
    fun `samples below confidence threshold are discarded and return null`() {
        val smoother = EmotionSmoother(windowSize = 3, confidenceThreshold = 0.6f)

        repeat(3) {
            assertNull(smoother.addSample("happy", 0.5f))
        }
    }

    @Test
    fun `confidence equal to threshold is accepted`() {
        val smoother = EmotionSmoother(windowSize = 3, confidenceThreshold = 0.6f)

        assertNull(smoother.addSample("happy", 0.6f))
        assertNull(smoother.addSample("happy", 0.6f))
        assertEquals("happy", smoother.addSample("happy", 0.6f))
    }

    @Test
    fun `returns null while window is not full`() {
        val smoother = EmotionSmoother(windowSize = 3)

        repeat(2) {
            assertNull(smoother.addSample("happy", 0.9f))
        }
    }

    @Test
    fun `majority vote outputs stable label when window fills`() {
        val smoother = EmotionSmoother(windowSize = 3)

        assertNull(smoother.addSample("happy", 0.9f))
        assertNull(smoother.addSample("sad", 0.9f))
        // 窗口: happy x2, sad x1 → happy 多数
        assertEquals("happy", smoother.addSample("happy", 0.9f))
    }

    @Test
    fun `hysteresis keeps previous stable label when no majority`() {
        val smoother = EmotionSmoother(windowSize = 3)

        assertNull(smoother.addSample("happy", 0.9f))
        assertNull(smoother.addSample("happy", 0.9f))
        assertEquals("happy", smoother.addSample("happy", 0.9f))

        // 窗口: h h s → happy 仍多数，维持 happy
        assertEquals("happy", smoother.addSample("sad", 0.9f))
        // 窗口: h s n → 三票分散无过半，维持 happy
        assertEquals("happy", smoother.addSample("neutral", 0.9f))
    }

    @Test
    fun `label switches when new label gains majority`() {
        val smoother = EmotionSmoother(windowSize = 3)

        assertNull(smoother.addSample("happy", 0.9f))
        assertNull(smoother.addSample("happy", 0.9f))
        assertEquals("happy", smoother.addSample("happy", 0.9f))

        // 窗口: h h s → 仍 happy
        assertEquals("happy", smoother.addSample("sad", 0.9f))
        // 窗口: h s s → sad 过半，切换
        assertEquals("sad", smoother.addSample("sad", 0.9f))
    }

    @Test
    fun `low confidence sample does not pollute the window`() {
        val smoother = EmotionSmoother(windowSize = 3)

        assertNull(smoother.addSample("happy", 0.9f))
        assertNull(smoother.addSample("happy", 0.9f))
        assertEquals("happy", smoother.addSample("happy", 0.9f))

        // 低置信度样本返回 null，且不进入窗口
        assertNull(smoother.addSample("sad", 0.3f))

        // 窗口未被污染，后续高置信 happy 仍是绝对多数
        assertEquals("happy", smoother.addSample("happy", 0.9f))
    }
}
