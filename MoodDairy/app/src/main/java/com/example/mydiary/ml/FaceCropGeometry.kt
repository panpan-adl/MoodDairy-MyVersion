package com.example.mydiary.ml

import kotlin.math.ceil
import kotlin.math.max

object FaceCropGeometry {
    data class Bounds(val left: Int, val top: Int, val size: Int)
    /** Allow small detector overshoots. Large missing face areas are still rejected. */
    fun square(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): Bounds? {
        if (width <= 0 || height <= 0 || right <= left || bottom <= top) return null
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        if (r <= l || b <= t) return null
        val visible = (r - l).toDouble() * (b - t)
        val detected = (right.toDouble() - left) * (bottom.toDouble() - top)
        if (visible / detected < 0.70) return null
        val side = ceil(max(r - l, b - t) * 1.10).toInt()
        fun origin(start: Int, end: Int, limit: Int): Int {
            val centered = (start + end - side) / 2
            return if (side <= limit) centered.coerceIn(0, limit - side) else centered
        }
        // Bounds may exceed the image for a close face. The bitmap layer pads with edge pixels.
        return Bounds(origin(l, r, width), origin(t, b, height), side)
    }
}
