package com.example.mydiary.ml

import android.graphics.Bitmap

/**
 * 人脸图像预处理器（纯数学部分）
 *
 * 不依赖 android.graphics.Bitmap，可在 JVM 单元测试中直接调用。
 */
object FaceImagePreprocessor {

    /** 作者定义的 RGB / 255、ImageNet 均值标准差、NCHW 平面排列。 */
    fun pixelsToRgbNchw(pixels: IntArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val count = pixels.size
        val result = FloatArray(count * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            result[i] = (((pixel shr 16) and 255) / 255f - 0.485f) / 0.229f
            result[count + i] = (((pixel shr 8) and 255) / 255f - 0.456f) / 0.224f
            result[2 * count + i] = ((pixel and 255) / 255f - 0.406f) / 0.225f
        }
        return result
    }

    /** Horizontal test-time augmentation in normalized NCHW order. */
    fun flipRgbNchw(input: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && input.size == 3 * width * height)
        return FloatArray(input.size) { index ->
            val plane = index / (width * height)
            val pixel = index % (width * height)
            input[plane * width * height + (pixel / width) * width + width - 1 - pixel % width]
        }
    }

    /**
     * 将 ARGB_8888 像素数组转换为归一化灰度 float 数组（HWC 排布）
     *
     * 与 FER2013 mini-XCEPTION 官方预处理（oarriaga/face_classification）严格对齐：
     *   1. 亮度按 ITU-R BT.601 加权：Y = 0.299R + 0.587G + 0.114B（同 cv2.cvtColor BGR2GRAY）
     *   2. Y / 255.0
     *   3. (Y - 0.5) * 2.0  → 值域 [-1.0, 1.0]
     *
     * @param pixels ARGB_8888 像素数组，长度必须等于 width * height
     * @param width 图像宽度
     * @param height 图像高度
     * @return 归一化灰度数组（HWC 排布：[height, width, 1]），长度 = width * height，取值 -1.0~1.0
     */
    fun pixelsToGrayNormalized(pixels: IntArray, width: Int, height: Int): FloatArray {
        require(pixels.size == width * height) {
            "pixels size (${pixels.size}) must equal width * height (${width * height})"
        }
        val result = FloatArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // 加权亮度（与 OpenCV COLOR_BGR2GRAY 同一组系数）
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            // /255 → 中心化 → 放大到 [-1, 1]
            result[i] = (gray / 255.0f - 0.5f) * 2.0f
        }
        return result
    }
}

/**
 * Bitmap 胶水层：人脸 Bitmap → 260x260 归一化 RGB NCHW 输入
 *
 * 依赖 android.graphics.Bitmap，不可在 JVM 单元测试中调用。
 *
 * @param faceBitmap 人脸区域 Bitmap
 * @return 长度 3 * 260 * 260 的归一化 RGB 数组
 */
fun preprocessFaceBitmap(faceBitmap: Bitmap): FloatArray {
    val size = EmotionModelConfig.INPUT_SIZE
    val scaled = Bitmap.createScaledBitmap(faceBitmap, size, size, true)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    if (scaled != faceBitmap) {
        scaled.recycle()
    }
    return FaceImagePreprocessor.pixelsToRgbNchw(pixels, size, size)
}
