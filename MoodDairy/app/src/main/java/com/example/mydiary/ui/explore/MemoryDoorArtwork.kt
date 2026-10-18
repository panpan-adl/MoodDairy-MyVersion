package com.example.mydiary.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.TextPrimary

@Composable
internal fun IllustratedMemoryDoor(mailbox: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (mailbox) Color(0xFF94703E) else Color(0xFF3C7E6C)
    val paper = if (mailbox) listOf(Color(0xFFF7EEDB), Color(0xFFFFFCF3))
        else listOf(Color(0xFFDDEEE5), Color(0xFFF5FAF1))
    val shape = RoundedCornerShape(25.dp)
    Column(modifier.clip(shape).background(Brush.verticalGradient(paper))
        .border(1.dp, Color.White.copy(alpha = .85f), shape).clickable(onClick = onClick)
        .padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(ink.copy(alpha = .65f), CircleShape))
            Text(if (mailbox) "把此刻寄给未来" else "让心事有处安放", color = ink, fontSize = 10.sp,
                letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp).weight(1f))
        }
        MemoryDoorArtwork(mailbox, Modifier.fillMaxWidth().height(84.dp))
        Text(if (mailbox) "时光信箱" else "情绪空间", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mailbox) "打开信箱" else "走进空间", color = ink, fontSize = 11.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(27.dp).background(Color.White.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.NorthEast, null, tint = ink, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Small native illustrations: the envelope holds a letter; the room holds keepsakes. */
@Composable
private fun MemoryDoorArtwork(mailbox: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val sx = size.width / 160f
        val sy = size.height / 110f
        fun point(x: Float, y: Float) = Offset(x * sx, y * sy)
        fun line(color: Color, x: Float, y: Float, x2: Float, y2: Float, width: Float = 1.5f) =
            drawLine(color, point(x, y), point(x2, y2), width * sy, StrokeCap.Round)
        fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float = 5f) =
            drawRoundRect(color, point(x, y), Size(w * sx, h * sy), CornerRadius(radius * sy))
        val green = Color(0xFF5B917A)
        val gold = Color(0xFFC49B59)
        drawOval(if (mailbox) Color(0xFFECE1C8) else Color(0xFFD3E5D7), point(21f, 93f), Size(120f * sx, 11f * sy))
        drawCircle(Color.White.copy(alpha = .52f), 44f * sy, point(81f, 53f))
        if (mailbox) {
            // A slow postmark trail and calendar hint at delivery through time.
            drawPath(Path().apply {
                moveTo(12f * sx, 64f * sy); cubicTo(0f, 15f * sy, 78f * sx, -2f * sy, 142f * sx, 39f * sy)
            }, gold.copy(alpha = .48f), style = Stroke(1.1f * sy, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * sy, 5f * sy))))
            rect(Color(0xFFE6D4AF), 112f, 25f, 34f, 33f, 6f)
            rect(Color(0xFFFFFCF3), 111f, 21f, 34f, 32f, 6f)
            rect(gold.copy(alpha = .55f), 111f, 21f, 34f, 9f, 4f)
            line(gold, 120f, 18f, 120f, 25f, 2f); line(gold, 136f, 18f, 136f, 25f, 2f)
            repeat(3) { i -> drawCircle(gold.copy(alpha = .65f), 1.7f * sy, point(120f + i * 8f, 39f)) }
            rect(Color(0xFFFFFEF7), 41f, 14f, 63f, 65f, 6f)
            repeat(3) { i -> line(Color(0xFFB8CEBA), 52f, 29f + i * 9f, 91f - i * 5f, 29f + i * 9f, 2f) }
            rect(Color(0xFFCCAB72), 25f, 49f, 91f, 47f, 8f)
            drawPath(Path().apply { moveTo(27f * sx, 52f * sy); lineTo(70f * sx, 79f * sy); lineTo(114f * sx, 52f * sy) },
                Color(0xFFF7EACF), style = Stroke(1.8f * sy, cap = StrokeCap.Round))
            line(Color(0xFFB18C54), 29f, 92f, 57f, 71f, 1f); line(Color(0xFFB18C54), 112f, 92f, 84f, 71f, 1f)
            drawCircle(Color(0xFFFFF2CF), 8f * sy, point(71f, 76f))
            drawCircle(gold, 4f * sy, point(71f, 76f), style = Stroke(1.2f * sy))
            drawCircle(Color(0xFF9ABC9F), 3f * sy, point(21f, 27f))
        } else {
            // A miniature open room, with a shelf, a picture, a keepsake and a plant.
            drawCircle(Color(0xFFECDAB0), 12f * sy, point(128f, 20f))
            drawPath(Path().apply { moveTo(26f * sx, 47f * sy); lineTo(76f * sx, 10f * sy); lineTo(127f * sx, 47f * sy) },
                green, style = Stroke(4f * sy, cap = StrokeCap.Round))
            rect(Color(0xFFEEF5E7), 33f, 44f, 86f, 52f, 5f)
            rect(Color(0xFFD9E8D4), 84f, 44f, 35f, 52f, 3f)
            line(green.copy(alpha = .6f), 33f, 47f, 33f, 96f, 2f)
            line(green.copy(alpha = .6f), 119f, 47f, 119f, 96f, 2f)
            rect(Color(0xFFC6AB7D), 48f, 38f, 27f, 29f, 3f)
            rect(Color(0xFFFFFCF1), 51f, 41f, 21f, 23f, 1f)
            drawCircle(Color(0xFFE7CE91), 4f * sy, point(65f, 47f))
            drawPath(Path().apply { moveTo(53f * sx, 60f * sy); lineTo(60f * sx, 51f * sy); lineTo(69f * sx, 60f * sy); close() }, Color(0xFFA4C2A1))
            rect(Color(0xFFC6AB7D), 43f, 69f, 62f, 4f, 2f)
            rect(Color(0xFF91B7A1), 48f, 78f, 21f, 18f, 3f)
            line(Color(0xFFE3EEDF), 53f, 82f, 53f, 92f, 1f)
            rect(Color(0xFFE6CB96), 76f, 83f, 18f, 13f, 3f)
            drawCircle(Color(0xFFF8EBD0), 3f * sy, point(85f, 84f))
            line(green, 132f, 85f, 132f, 51f, 2f)
            drawOval(Color(0xFF8EB69A), point(118f, 53f), Size(14f * sx, 8f * sy))
            drawOval(green.copy(alpha = .8f), point(132f, 62f), Size(15f * sx, 9f * sy))
            drawOval(Color(0xFFA5C5A4), point(121f, 70f), Size(11f * sx, 7f * sy))
            rect(Color(0xFFCEB58E), 122f, 83f, 21f, 16f, 4f)
        }
        val sparkle = if (mailbox) gold else green.copy(alpha = .6f)
        line(sparkle, 145f, 77f, 145f, 85f, 1.2f); line(sparkle, 141f, 81f, 149f, 81f, 1.2f)
        drawCircle(sparkle.copy(alpha = .6f), 2f * sy, point(15f, 89f))
    }
}
