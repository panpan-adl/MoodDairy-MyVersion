package com.example.mydiary.ui.editor

import kotlin.math.*

/** Geometry is independent of Android so narrow screens and font scaling can be verified on the JVM. */
internal data class CloudWordSize(val width: Float, val height: Float, val angle: Float)
internal data class CloudWordPlacement(val centerX: Float, val centerY: Float, val width: Float, val height: Float) {
    val left get() = centerX - width / 2
    val right get() = centerX + width / 2
    val top get() = centerY - height / 2
    val bottom get() = centerY + height / 2
    fun overlaps(other: CloudWordPlacement, gap: Float): Boolean =
        left < other.right + gap && right + gap > other.left && top < other.bottom + gap && bottom + gap > other.top
}
internal data class DiaryCloudGeometry(val height: Float, val words: List<CloudWordPlacement>)

internal fun arrangeDiaryCloud(width: Float, sizes: List<CloudWordSize>, gap: Float): DiaryCloudGeometry {
    require(width > 0 && gap >= 0)
    if (sizes.isEmpty()) return DiaryCloudGeometry(width * .42f, emptyList())
    val bounds = sizes.map { size ->
        val angle = Math.toRadians(size.angle.toDouble())
        (abs(cos(angle)) * size.width + abs(sin(angle)) * size.height).toFloat() to
            (abs(sin(angle)) * size.width + abs(cos(angle)) * size.height).toFloat()
    }
    require(bounds.all { it.first <= width - gap * 2 }) { "Word must fit within the cloud including rotation" }
    val anchors = listOf(.43f to .22f, .74f to .43f, .23f to .51f, .72f to .69f,
        .32f to .76f, .79f to .13f, .15f to .30f, .49f to .50f,
        .31f to .07f, .73f to .91f, .16f to .93f, .61f to .04f)
    var height = max(width * (if (sizes.size <= 3) .42f else .62f),
        bounds.sumOf { (it.first * it.second).toDouble() }.toFloat() / (width * .66f))
    height = max(height, bounds.maxOf { it.second } + gap * 2)
    // Each retry grows the drawing, never shrinks type or overlaps words.
    while (true) {
      for (attempt in 0..6) {
        val placed = mutableListOf<CloudWordPlacement>()
        for ((index, box) in bounds.withIndex()) {
            val (bw, bh) = box
            val (ax, ay) = anchors[index % anchors.size]
            val targetX = (width * ax).coerceIn(gap + bw / 2, width - gap - bw / 2)
            val anchorY = if (attempt == 0) ay else (ay + attempt * .17f) % 1f
            val targetY = (height * anchorY).coerceIn(gap + bh / 2, height - gap - bh / 2)
            val candidates = mutableListOf(targetX to targetY)
            for (row in 0..45) for (col in 0..24) {
                candidates += (gap + bw / 2 + (width - 2 * gap - bw) * col / 24) to
                    (gap + bh / 2 + (height - 2 * gap - bh) * row / 45)
            }
            val found = candidates.asSequence().map { (x, y) -> CloudWordPlacement(x, y, bw, bh) }
                .filter { candidate -> placed.none { candidate.overlaps(it, gap) } }
                .minByOrNull {
                    // Dense fallback fills remaining upper space before growing the picture.
                    if (attempt == 6) it.top * width * 100f + it.left
                    else (it.centerX - targetX).pow(2) + (it.centerY - targetY).pow(2)
                }
            if (found == null) break
            placed += found
        }
        if (placed.size == sizes.size) return DiaryCloudGeometry(height, placed)
      }
        height *= 1.18f
    }
}

internal fun diaryWordAngle(index: Int): Float = floatArrayOf(-5f, 7f, -8f, 5f, 9f, 6f, -4f, -3f, 3f, -7f, -6f, 4f)[index % 12]
internal fun diaryWordFontSize(weight: Float, minimum: Float, maximum: Float): Float =
    minimum + (maximum - minimum) * (weight.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f).pow(1.3f)

/** Failed refreshes keep the existing picture closed/open exactly as the reader left it. */
internal fun shouldOpenDiaryReview(waiting: Boolean, generating: Boolean, available: Boolean, error: String?): Boolean =
    waiting && !generating && available && error.isNullOrBlank()

internal fun diaryFittingAngle(index: Int, wordWidth: Float, wordHeight: Float, cloudWidth: Float, gap: Float): Float {
    val angle = diaryWordAngle(index)
    val radians = Math.toRadians(angle.toDouble())
    return if (abs(cos(radians)) * wordWidth + abs(sin(radians)) * wordHeight <= cloudWidth - gap * 2) angle else 0f
}

/** Shared by flight animation and trails; the mouth sits inside the envelope below the cloud. */
internal fun diaryEnvelopeMouthOffset(width: Float): Float = width * .40f * .70f

internal fun diaryCloudMaximumFont(widthDp: Float): Float = (widthDp / 14.3f).coerceIn(20f, 26f)

internal fun diaryFlightPosition(originX: Float, originY: Float, targetX: Float, targetY: Float,
    cloudHeight: Float, bend: Float, progress: Float): Pair<Float, Float> {
    val t = progress.coerceIn(0f, 1f)
    val r = 1f - t
    val c1x = originX + bend
    val c1y = cloudHeight * .72f
    val c2x = targetX + bend * .38f
    val c2y = targetY + abs(bend)
    return (r*r*r*originX + 3*r*r*t*c1x + 3*r*t*t*c2x + t*t*t*targetX) to
        (r*r*r*originY + 3*r*r*t*c1y + 3*r*t*t*c2y + t*t*t*targetY)
}
