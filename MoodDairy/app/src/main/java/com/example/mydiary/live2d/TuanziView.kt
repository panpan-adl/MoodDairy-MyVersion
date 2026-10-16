package com.example.mydiary.live2d

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Bundled animated cat, independent of missing model files and OpenGL. */
class TuanziView(context: Context) : View(context) {
    private val ink = Color.rgb(91, 75, 66)
    private val fur = Color.rgb(255, 247, 225)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { contentDescription = "团子"; isClickable = true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 128f, height / 128f)
        canvas.translate(0f, sin(phase * Math.PI * 2).toFloat() * 1.5f)
        fun oval(l: Float, t: Float, r: Float, b: Float, color: Int) {
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawOval(RectF(l, t, r, b), paint)
        }
        fun line(x: Float, y: Float, x2: Float, y2: Float, color: Int = ink, width: Float = 2.2f) {
            paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, y, x2, y2, paint)
        }
        fun triangle(x: Float, y: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawPath(Path().apply { moveTo(x,y); lineTo(x2,y2); lineTo(x3,y3); close() }, paint)
        }
        oval(18f, 110f, 114f, 122f, Color.argb(25, 50, 90, 70))
        paint.style = Paint.Style.STROKE; paint.color = ink; paint.strokeWidth = 17f
        paint.strokeCap = Paint.Cap.ROUND
        val tail = Path().apply {
            moveTo(94f, 102f)
            cubicTo(130f, 99f, 122f, 65f, 111f + sin(phase * Math.PI * 2).toFloat()*4, 64f)
        }
        canvas.drawPath(tail, paint)
        paint.color = fur; paint.strokeWidth = 12f; canvas.drawPath(tail, paint)
        oval(28f, 65f, 101f, 119f, ink); oval(31f, 67f, 98f, 116f, fur)
        oval(39f, 79f, 91f, 116f, Color.WHITE)
        triangle(18f, 49f, 22f, 8f, 52f, 31f, ink)
        triangle(76f, 31f, 107f, 8f, 111f, 52f, ink)
        triangle(23f, 44f, 25f, 15f, 48f, 33f, fur)
        triangle(81f, 33f, 104f, 15f, 107f, 44f, fur)
        triangle(29f, 34f, 28f, 23f, 41f, 34f, Color.rgb(244, 174, 177))
        triangle(88f, 34f, 101f, 23f, 101f, 36f, Color.rgb(244, 174, 177))
        oval(15f, 26f, 113f, 91f, ink); oval(18f, 29f, 110f, 88f, fur)
        oval(28f, 58f, 44f, 69f, Color.rgb(251, 202, 195))
        oval(84f, 58f, 100f, 69f, Color.rgb(251, 202, 195))
        if (phase > .90f && phase < .95f) {
            line(40f, 53f, 49f, 53f); line(79f, 53f, 88f, 53f)
        } else {
            oval(41f, 47f, 48f, 57f, ink); oval(80f, 47f, 87f, 57f, ink)
            oval(42f, 48f, 44f, 51f, Color.WHITE); oval(81f, 48f, 83f, 51f, Color.WHITE)
        }
        triangle(59f, 59f, 69f, 59f, 64f, 64f, Color.rgb(220, 144, 147))
        line(64f, 64f, 59f, 68f); line(64f, 64f, 69f, 68f)
        line(21f, 57f, 34f, 59f, ink, 1.5f); line(21f, 65f, 34f, 63f, ink, 1.5f)
        line(94f, 59f, 107f, 57f, ink, 1.5f); line(94f, 63f, 107f, 65f, ink, 1.5f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(117, 195, 164)
        canvas.drawRoundRect(RectF(32f, 83f, 97f, 94f), 5f, 5f, paint)
        oval(59f, 87f, 70f, 98f, Color.rgb(250, 208, 108))
        oval(29f, 103f, 56f, 118f, ink); oval(32f, 105f, 54f, 116f, fur)
        oval(74f, 103f, 101f, 118f, ink); oval(76f, 105f, 98f, 116f, fur)
        canvas.restore()
    }
}
