package com.example.mydiary.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

internal val DiaryCream = Color(0xFFFCFBF6)
internal val DiaryGreen = Color(0xFF4C8052)
internal val DiaryMutedGreen = Color(0xFF7C947D)

internal fun DrawScope.drawDiaryGlow() {
    drawRect(DiaryCream)
    drawRect(Brush.radialGradient(listOf(Color(0xFFD8F0DC), Color.Transparent),
        center = Offset(size.width * 1.02f, size.height * .28f), radius = size.width * .85f))
    drawRect(Brush.radialGradient(listOf(Color(0xFFDDF1E3), Color.Transparent),
        center = Offset(size.width * .28f, size.height * .91f), radius = size.width * 1.12f))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .85f), Color.Transparent),
        center = Offset(size.width * .20f, size.height * .16f), radius = size.width * .8f))
    val random = Random(481)
    repeat(1600) {
        drawCircle(Color(0xFF7A9B7A).copy(alpha = .035f), radius = .35.dp.toPx(),
            center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height))
    }
}

@Composable
internal fun DiaryCloudTrails(geometry: DiaryCloudGeometry, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val origin = Offset(size.width * .5f, size.height + diaryEnvelopeMouthOffset(size.width + 16.dp.toPx()))
        geometry.words.forEachIndexed { index, word ->
            val end = Offset(word.centerX, word.centerY)
            val bend = if (index % 2 == 0) -1 else 1
            val path = Path().apply {
                moveTo(origin.x, origin.y)
                cubicTo(origin.x + bend * size.width * .14f, size.height * .72f,
                    end.x + bend * size.width * .14f * .38f, end.y + size.width * .14f, end.x, end.y)
            }
            drawPath(path, Color(0xFF91C796).copy(alpha = .42f * progress),
                style = Stroke(.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))))
        }
        val leaves = listOf(.08f to .66f, .91f to .46f, .78f to .03f,
            .19f to .14f, .88f to .85f, .35f to .90f, .53f to .61f)
        leaves.forEachIndexed { index, (x, y) ->
            val center = Offset(size.width * x, size.height * y)
            rotate(if (index % 2 == 0) -28f else 35f, center) {
                val leaf = Path().apply {
                    moveTo(center.x, center.y + 7.dp.toPx())
                    quadraticTo(center.x - 12.dp.toPx(), center.y, center.x, center.y - 9.dp.toPx())
                    quadraticTo(center.x + 9.dp.toPx(), center.y, center.x, center.y + 7.dp.toPx())
                }
                drawPath(leaf, Brush.linearGradient(listOf(Color(0xFFA7D69B).copy(alpha = progress * .7f),
                    Color(0xFF81BA7C).copy(alpha = progress * .48f))))
                drawLine(Color(0xFF81B57C).copy(alpha = progress * .35f),
                    center + Offset(0f, 6.dp.toPx()), center - Offset(0f, 7.dp.toPx()), .5.dp.toPx())
            }
        }
        listOf(.10f to .38f, .90f to .31f, .67f to .14f, .07f to .83f, .94f to .72f).forEachIndexed { i, (x, y) ->
            drawCircle((if (i % 2 == 0) Color(0xFFF3C7D4) else Color(0xFFB8E5D8)).copy(alpha = progress * .65f),
                (if (i % 2 == 0) 2.6f else 1.8f).dp.toPx(), Offset(size.width * x, size.height * y))
        }
    }
}

@Composable
internal fun DiaryPaperEnvelope(flap: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // All coordinates share the same paper silhouette, texture and fold boundaries.
        val left = size.width * .12f; val right = size.width * .88f
        val top = size.height * .31f; val bottom = size.height * .93f
        val center = size.width * .5f; val mouth = size.height * .70f
        val paper = Path().apply {
            moveTo(left, top); lineTo(center, top - size.height * .27f * flap); lineTo(right, top)
            lineTo(right, bottom); lineTo(left, bottom); close()
        }
        for (i in 12 downTo 1) {
            val spread = i * .65.dp.toPx()
            drawRoundRect(Color(0xFF6D9871).copy(alpha = .006f * (13 - i)),
                Offset(left - spread, top + 5.dp.toPx() - spread / 2),
                androidx.compose.ui.geometry.Size(right - left + spread * 2, bottom - top + spread),
                CornerRadius(5.dp.toPx()))
        }
        drawPath(paper, Brush.linearGradient(listOf(Color.White, Color(0xFFF2F6EB)),
            Offset(left, top), Offset(right, bottom)))
        val inner = Path().apply { moveTo(left, top); lineTo(center, top - size.height * .15f * flap); lineTo(right, top); lineTo(center, mouth); close() }
        drawPath(inner, Brush.verticalGradient(listOf(Color(0xFFE9F3E7), Color(0xFFC9E0CA)), top, mouth))
        val leftFold = Path().apply { moveTo(left, top); lineTo(center + 7.dp.toPx(), mouth); lineTo(left, bottom); close() }
        val rightFold = Path().apply { moveTo(right, top); lineTo(center - 7.dp.toPx(), mouth); lineTo(right, bottom); close() }
        drawPath(leftFold, Brush.linearGradient(listOf(Color(0xFFFEFEF9), Color(0xFFE8EEE4)), Offset(left, top), Offset(center, mouth)))
        drawPath(rightFold, Brush.linearGradient(listOf(Color.White, Color(0xFFEAF0E5)), Offset(right, top), Offset(center, mouth)))
        val front = Path().apply { moveTo(left, bottom); lineTo(center, size.height * .56f); lineTo(right, bottom); close() }
        drawPath(front, Brush.verticalGradient(listOf(Color(0xFFF7FBF2), Color.White), mouth, bottom))
        drawLine(Color(0xFFD8E6D4), Offset(left, bottom), Offset(center, size.height * .56f), .6.dp.toPx())
        drawLine(Color(0xFFD8E6D4), Offset(right, bottom), Offset(center, size.height * .56f), .6.dp.toPx())
        // Fine paper fibers stay clipped to the paper rather than tinting the whole scene.
        clipPath(paper) {
            val random = Random(761)
            repeat(1600) {
                val point = Offset(left + random.nextFloat() * (right - left), random.nextFloat() * bottom)
                drawLine(Color(0xFF8AA082).copy(alpha = .045f), point, point + Offset(1.dp.toPx(), .2.dp.toPx()), .35.dp.toPx())
            }
        }
        // The closed lid flips towards the back; the fully open back triangle is part of the silhouette.
        if (flap < 1f) {
            val fold = Path().apply {
                moveTo(left, top); lineTo(right, top)
                lineTo(center, top + (mouth - top) * cos(flap * Math.PI).toFloat()); close()
            }
            drawPath(fold, Brush.verticalGradient(listOf(Color(0xFFFCFEF7), Color(0xFFE6EDDF)), top, mouth),
                alpha = (1f - ((flap - .65f) / .35f).coerceIn(0f, 1f)))
        }
    }
}

internal fun diaryWordColors(category: String, index: Int): Pair<Color, Color> = when (category) {
    "joy", "hope" -> Color(0xFFFCEDBC) to Color(0xFF627A46)
    "calm" -> Color(0xFFDCEFE4) to Color(0xFF4D806A)
    "sadness", "anxiety" -> Color(0xFFE8DDF0) to Color(0xFF77638C)
    "fatigue", "anger" -> Color(0xFFE0E9EE) to Color(0xFF617C89)
    else -> when (index % 4) {
        0 -> Color(0xFFFFFCF2) to Color(0xFF687F55)
        1 -> Color(0xFFF5E0DF) to Color(0xFF747C5B)
        2 -> Color(0xFFDDF0EC) to Color(0xFF5B8572)
        else -> Color(0xFFFAF0D0) to Color(0xFF6D8150)
    }
}
