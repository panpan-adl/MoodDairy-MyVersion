package com.example.mydiary.live2d

import kotlin.math.roundToInt

/** Composer-owned window coordinates; an old page cannot clear its replacement. */
internal class CompanionBoundary {
    private var owner: Any? = null
    var topInWindow: Float? = null
        private set
    fun update(token: Any, top: Float?) {
        if (top == null) {
            if (owner === token) { owner = null; topInWindow = null }
        } else if (top.isFinite() && top >= 0f) {
            owner = token
            topInWindow = top
        }
    }
}

internal object CompanionAnchorGeometry {
    fun top(boundaryInWindow: Float, parentInWindow: Int, parentPadding: Int, overlayHeight: Int): Int =
        (boundaryInWindow - parentInWindow - parentPadding).roundToInt() - overlayHeight
    fun horizontal(requested: Int, parentWidth: Int, leftPadding: Int, rightPadding: Int, overlayWidth: Int): Int =
        requested.coerceIn(0, (parentWidth - leftPadding - rightPadding - overlayWidth).coerceAtLeast(0))
}
