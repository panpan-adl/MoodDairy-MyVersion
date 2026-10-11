package com.example.mydiary.ml

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceEmotionInferenceTest {
    @Test fun closeFaceCropPadsEdgesAndKeepsFullFace() {
        val frame = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        frame.eraseColor(0xff00aa88.toInt())
        val bounds = FaceCropGeometry.square(-2, 0, 200, 280, 200, 300)!!
        val crop = FaceBitmapCropper.crop(frame, bounds, 0f)
        try {
            assertEquals(bounds.size, crop.width)
            assertEquals(crop.width, crop.height)
            assertEquals(0xff00aa88.toInt(), crop.getPixel(0, 0))
            assertEquals(0xff00aa88.toInt(), crop.getPixel(crop.width - 1, crop.height - 1))
        } finally { crop.recycle(); frame.recycle() }
    }

    @Test fun tiltedCropHasNoEmptyBlackCorners() {
        val frame = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        frame.eraseColor(0xffddaa88.toInt())
        val crop = FaceBitmapCropper.crop(frame, FaceCropGeometry.square(10, 10, 290, 380, 300, 400)!!, 15f)
        try {
            for (x in listOf(0, crop.width - 1)) for (y in listOf(0, crop.height - 1)) {
                assertEquals(0xffddaa88.toInt(), crop.getPixel(x, y))
            }
        } finally { crop.recycle(); frame.recycle() }
    }

    /** Device smoke only: synthetic pixels have no expression ground truth. */
    @Test fun bundledModelRunsBothViewsOnAndroidAndReturnsValidDistribution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val analyzer = FaceEmotionAnalyzer(context)
        val bitmap = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888)
        try {
            assertTrue("Bundled ONNX model must load", analyzer.isAvailable)
            bitmap.setPixels(IntArray(280 * 280) { i ->
                0xff000000.toInt() or ((i % 256) shl 16) or (((i / 280) % 256) shl 8) or (i % 127)
            }, 0, 280, 0, 0, 280, 280)
            val started = SystemClock.elapsedRealtime()
            val probabilities = analyzer.analyzeProbabilities(bitmap)
            Log.i("EmotionInferenceTest", "Two-view Android inference ms=${SystemClock.elapsedRealtime() - started}")
            assertNotNull(probabilities)
            assertEquals(7, probabilities!!.size)
            assertTrue(probabilities.all { it.isFinite() && it in 0f..1f })
            assertEquals(1f, probabilities.sum(), .0001f)
        } finally {
            bitmap.recycle()
            analyzer.close()
        }
    }
}
