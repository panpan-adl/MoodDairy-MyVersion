package com.example.mydiary.ui.drawing

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.LineWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.TextSecondary
import com.example.mydiary.ui.theme.TextTertiary
import kotlin.math.hypot

data class DrawingStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
)

@Composable
fun DrawingCanvas(
    modifier: Modifier = Modifier,
    onBitmapReady: ((Bitmap?) -> Unit)? = null,
) {
    var strokes by remember { mutableStateOf(emptyList<DrawingStroke>()) }
    var currentStroke by remember { mutableStateOf<DrawingStroke?>(null) }
    var strokeColor by remember { mutableStateOf(Color.Black) }
    var strokeWidth by remember { mutableStateOf(8f) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showStrokePicker by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val colors = listOf(
        Color.Black,
        Color(0xFF424242),
        Color(0xFFE53935),
        Color(0xFFFF9800),
        Color(0xFFFFC107),
        Color(0xFF4CAF50),
        Color(0xFF2196F3),
        Color(0xFF9C27B0),
        Color(0xFF795548),
    )
    val strokeWidths = listOf(4f, 8f, 12f, 18f, 24f)

    LaunchedEffect(strokes, canvasSize) {
        onBitmapReady?.invoke(renderBitmap(strokes, canvasSize))
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { showColorPicker = !showColorPicker },
                        color = strokeColor,
                        shape = CircleShape,
                        shadowElevation = 2.dp,
                    ) {}

                    DropdownMenu(
                        expanded = showColorPicker,
                        onDismissRequest = { showColorPicker = false },
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            colors.forEach { color ->
                                Surface(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (color == strokeColor) 2.dp else 0.dp,
                                            color = Primary,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            strokeColor = color
                                            showColorPicker = false
                                        },
                                    color = color,
                                    shape = CircleShape,
                                ) {}
                            }
                        }
                    }
                }

                Box {
                    IconButton(onClick = { showStrokePicker = !showStrokePicker }) {
                        Icon(
                            imageVector = Icons.Outlined.LineWeight,
                            contentDescription = "Brush size",
                            tint = TextSecondary,
                        )
                    }

                    DropdownMenu(
                        expanded = showStrokePicker,
                        onDismissRequest = { showStrokePicker = false },
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            strokeWidths.forEach { width ->
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            strokeWidth = width
                                            showStrokePicker = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(60.dp)
                                            .height(width.dp)
                                            .background(
                                                if (width == strokeWidth) Primary else Color.Gray,
                                                RoundedCornerShape(width / 2),
                                            ),
                                    )
                                    if (width == strokeWidth) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { strokes = strokes.dropLast(1) },
                    enabled = strokes.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = "Undo",
                        tint = if (strokes.isNotEmpty()) TextSecondary else TextTertiary,
                    )
                }

                IconButton(
                    onClick = { strokes = emptyList() },
                    enabled = strokes.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear",
                        tint = if (strokes.isNotEmpty()) TextSecondary else TextTertiary,
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(strokeWidth, strokeColor) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentStroke = DrawingStroke(
                                    points = listOf(offset),
                                    color = strokeColor,
                                    strokeWidth = strokeWidth,
                                )
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentStroke = currentStroke?.appendPoint(change.position)
                            },
                            onDragEnd = {
                                currentStroke?.let { stroke ->
                                    if (stroke.points.isNotEmpty()) {
                                        strokes = strokes + stroke
                                    }
                                }
                                currentStroke = null
                            },
                            onDragCancel = {
                                currentStroke = null
                            },
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    strokes.forEach { stroke ->
                        drawStroke(stroke)
                    }
                    currentStroke?.let { stroke ->
                        drawStroke(stroke)
                    }
                }
            }
        }

        if (strokes.isEmpty() && currentStroke == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Use your finger to draw on the canvas",
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun DrawingStroke.appendPoint(point: Offset): DrawingStroke {
    val lastPoint = points.lastOrNull() ?: return copy(points = listOf(point))

    val distance = hypot(point.x - lastPoint.x, point.y - lastPoint.y)
    if (distance < 1f) return this

    val maxStep = (strokeWidth / 2f).coerceIn(2f, 12f)
    val steps = kotlin.math.ceil(distance / maxStep).toInt().coerceAtLeast(1)
    val interpolatedPoints = (1..steps).map { step ->
        val progress = step / steps.toFloat()
        Offset(
            x = lastPoint.x + (point.x - lastPoint.x) * progress,
            y = lastPoint.y + (point.y - lastPoint.y) * progress,
        )
    }

    return copy(points = points + interpolatedPoints)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: DrawingStroke) {
    if (stroke.points.isEmpty()) {
        return
    }

    if (stroke.points.size == 1) {
        drawCircle(
            color = stroke.color,
            radius = stroke.strokeWidth / 2f,
            center = stroke.points.first(),
        )
        return
    }

    drawPath(
        path = buildSmoothPath(stroke.points),
        color = stroke.color,
        style = Stroke(
            width = stroke.strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun buildSmoothPath(points: List<Offset>): Path {
    return Path().apply {
        val first = points.first()
        moveTo(first.x, first.y)

        if (points.size == 2) {
            lineTo(points.last().x, points.last().y)
            return@apply
        }

        for (index in 1 until points.size - 1) {
            val prev = points[index - 1]
            val curr = points[index]
            val next = points[index + 1]

            val dx1 = curr.x - prev.x
            val dy1 = curr.y - prev.y
            val dx2 = next.x - curr.x
            val dy2 = next.y - curr.y

            val len1 = hypot(dx1, dy1)
            val len2 = hypot(dx2, dy2)
            val smoothness = 0.2f
            val cpScale = smoothness * kotlin.math.min(len1, len2)

            val cp1x = curr.x - (if (len1 > 0f) cpScale * dx1 / len1 else 0f)
            val cp1y = curr.y - (if (len1 > 0f) cpScale * dy1 / len1 else 0f)
            val cp2x = curr.x + (if (len2 > 0f) cpScale * dx2 / len2 else 0f)
            val cp2y = curr.y + (if (len2 > 0f) cpScale * dy2 / len2 else 0f)

            cubicTo(cp1x, cp1y, cp2x, cp2y, next.x, next.y)
        }
    }
}

private fun renderBitmap(strokes: List<DrawingStroke>, canvasSize: IntSize): Bitmap? {
    if (strokes.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) {
        return null
    }

    val bitmap = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    strokes.forEach { stroke ->
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = stroke.color.toArgb()
            strokeWidth = stroke.strokeWidth
        }

        if (stroke.points.size == 1) {
            val point = stroke.points.first()
            canvas.drawCircle(point.x, point.y, stroke.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
        } else {
            canvas.drawPath(buildSmoothPath(stroke.points).asAndroidPath(), paint)
        }
    }

    return bitmap
}
