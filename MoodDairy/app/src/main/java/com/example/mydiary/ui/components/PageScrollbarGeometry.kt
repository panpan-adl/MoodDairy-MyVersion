package com.example.mydiary.ui.components

internal data class ScrollbarFraction(val progress: Float, val visible: Float)
internal data class ScrollbarItem(val offset: Int, val size: Int)

/** Fractional item coverage handles partially visible and oversized rows without fake end positions. */
internal fun lazyScrollbarFraction(
    total: Int, firstIndex: Int, firstOffset: Int, firstSize: Int,
    items: List<ScrollbarItem>, viewportStart: Int, viewportEnd: Int,
    canBackward: Boolean, canForward: Boolean, reverse: Boolean,
): ScrollbarFraction {
    if (total <= 0 || viewportEnd <= viewportStart || items.isEmpty()) return ScrollbarFraction(0f, 1f)
    val coverage = items.sumOf {
        ((minOf(it.offset + it.size, viewportEnd) - maxOf(it.offset, viewportStart)).coerceAtLeast(0)
            .toDouble() / it.size.coerceAtLeast(1))
    }.toFloat().coerceIn(0f, total.toFloat())
    val visible = (coverage / total).coerceIn(0f, 1f)
    val leading = firstIndex + firstOffset.toFloat() / firstSize.coerceAtLeast(1)
    val progress = when {
        !canBackward -> 0f
        !canForward -> 1f
        else -> (leading / (total - coverage).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    }
    return ScrollbarFraction(if (reverse) 1f - progress else progress, visible)
}

internal data class ScrollbarThumb(val top: Float, val height: Float)
internal fun scrollbarThumb(trackHeight: Float, fraction: ScrollbarFraction, minimum: Float): ScrollbarThumb {
    val height = (trackHeight * fraction.visible).coerceIn(minOf(minimum, trackHeight), trackHeight)
    return ScrollbarThumb((trackHeight - height) * fraction.progress.coerceIn(0f, 1f), height)
}
