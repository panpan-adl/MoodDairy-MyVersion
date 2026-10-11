package com.example.mydiary.ml

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object FaceBitmapCropper {
    /** Keep the full square at image edges; repeat edge pixels instead of distorting or rejecting it. */
    fun crop(frame: Bitmap, bounds: FaceCropGeometry.Bounds, eyeAngle: Float): Bitmap {
        val size = bounds.size
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val angle = if (eyeAngle.isFinite() && abs(eyeAngle) in 2f..25f) eyeAngle else 0f
        val radians = Math.toRadians(angle.toDouble())
        val zoom = (abs(cos(radians)) + abs(sin(radians))).toFloat()
        val center = size / 2f
        val transform = Matrix().apply {
            setTranslate(-bounds.left.toFloat(), -bounds.top.toFloat())
            postRotate(-angle, center, center)
            postScale(zoom, zoom, center, center)
        }
        val shader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(transform)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(result).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return result
    }
}
