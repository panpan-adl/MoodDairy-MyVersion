package com.example.mydiary.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FaceImagePreprocessor 灰度归一化数学逻辑单元测试（纯 JVM，不依赖 Bitmap）
 *
 * 验证：ARGB_8888 像素 → BT.601 加权灰度 → /255 → (x-0.5)*2，值域 [-1, 1]
 * 与 FER2013 mini-XCEPTION 官方预处理（OpenCV BGR2GRAY 同系数）对齐。
 */
class FaceImagePreprocessorTest {

    /** (Y/255 - 0.5) * 2 的参考实现（Y 为浮点亮度，不做整数截断） */
    private fun expected(y: Double): Float = ((y / 255.0) - 0.5).toFloat() * 2.0f

    @Test
    fun `black pixel maps to minus one`() {
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFF000000.toInt()), 1, 1)
        assertEquals(1, result.size)
        assertEquals(-1f, result[0], 1e-6f)
    }

    @Test
    fun `white pixel maps to one`() {
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFFFFFFFF.toInt()), 1, 1)
        assertEquals(1f, result[0], 1e-4f)
    }

    @Test
    fun `pure red uses bt601 red weight`() {
        // R=255 → Y = 0.299*255
        val y = 0.299 * 255.0
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFFFF0000.toInt()), 1, 1)
        assertEquals(expected(y), result[0], 2e-3f)
    }

    @Test
    fun `pure green uses bt601 green weight`() {
        // G=255 → Y = 0.587*255
        val y = 0.587 * 255.0
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFF00FF00.toInt()), 1, 1)
        assertEquals(expected(y), result[0], 2e-3f)
    }

    @Test
    fun `pure blue uses bt601 blue weight`() {
        // B=255 → Y = 0.114*255
        val y = 0.114 * 255.0
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFF0000FF.toInt()), 1, 1)
        assertEquals(expected(y), result[0], 2e-3f)
    }

    @Test
    fun `mid gray maps near zero`() {
        // R=G=B=128 → Y=128 → (128/255-0.5)*2 ≈ 0.004
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(intArrayOf(0xFF808080.toInt()), 1, 1)
        assertEquals((128f / 255f - 0.5f) * 2f, result[0], 1e-4f)
    }

    @Test
    fun `output length equals width times height single channel`() {
        val pixels = IntArray(12) { 0xFF808080.toInt() }
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(pixels, 4, 3)
        assertEquals(12, result.size)
    }

    @Test
    fun `pixels are laid out in row major order`() {
        // 2 个像素：黑、白
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val result = FaceImagePreprocessor.pixelsToGrayNormalized(pixels, 2, 1)
        assertEquals(-1f, result[0], 1e-6f)
        assertEquals(1f, result[1], 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched pixel count throws`() {
        FaceImagePreprocessor.pixelsToGrayNormalized(IntArray(10), 4, 3)
    }
}
