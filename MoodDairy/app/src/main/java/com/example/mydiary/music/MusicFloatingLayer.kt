package com.example.mydiary.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.relax.RelaxPlaybackController
import com.example.mydiary.ui.theme.*
import kotlin.math.roundToInt

internal fun clampFloatingPosition(value: Float, available: Float): Float = value.coerceIn(0f, available.coerceAtLeast(0f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFloatingLayer(controller: RelaxPlaybackController, onChoose: () -> Unit) {
    val state by controller.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragX by rememberSaveable { mutableFloatStateOf(12f) }
    var dragY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    if (state.trackId == null) { return }
    // Root activity owns this layer, so changing destinations does not restart audio.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(bottom = 92.dp)) {
        val barWidth = minOf(340.dp, maxWidth - 24.dp)
        val availableX = with(density) { (maxWidth - barWidth).toPx() }
        val availableY = with(density) { maxHeight.toPx() } - measuredHeight
        val x = clampFloatingPosition(dragX, availableX)
        val y = clampFloatingPosition(if (dragY.isNaN()) availableY - with(density) { 240.dp.toPx() } else dragY, availableY)
        Surface(Modifier.width(barWidth).offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { measuredHeight = it.height }, shape = RoundedCornerShape(22.dp),
            color = Color.White, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryLight)) {
            Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).pointerInput(availableX, availableY) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        dragX = clampFloatingPosition((if (dragX.isNaN()) x else dragX) + amount.x, availableX)
                        dragY = clampFloatingPosition((if (dragY.isNaN()) y else dragY) + amount.y, availableY)
                    }
                }, contentAlignment = Alignment.Center) { Icon(Icons.Default.DragIndicator, "拖动播放器", tint = PrimaryDark) }
                Column(Modifier.weight(1f).clickable { expanded = true }.padding(vertical = 8.dp)) {
                    Text(state.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when { state.error != null -> "播放失败 · 点击重试"; state.loading -> "正在准备声音…"
                        state.playing -> if (state.whiteNoise) "白噪音 · 播放中" else "音乐 · 播放中"
                        else -> "已暂停 · 点击展开" }, color = PrimaryDark, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { controller.togglePause() }) {
                    Icon(if (state.playing || state.loading) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.playing || state.loading) "暂停" else "继续播放", tint = PrimaryDark)
                }
                IconButton(onClick = { controller.stop(); expanded = false }) { Icon(Icons.Default.Close, "关闭播放器", tint = TextSecondary) }
            }
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = SoftWhite) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(if (state.whiteNoise) "白噪音 · 循环播放" else state.artist, color = TextSecondary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.whiteNoise && state.durationMs > 0) {
                Slider(value = state.positionMs.coerceIn(0, state.durationMs).toFloat(), valueRange = 0f..state.durationMs.toFloat(),
                    onValueChange = { controller.seekTo(it.toLong()) }, colors = SliderDefaults.colors(thumbColor = PrimaryDark, activeTrackColor = PrimaryVariant))
                Text("${relaxTime(state.positionMs)} / ${relaxTime(state.durationMs)}", color = TextSecondary, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = PrimaryDark)
                Slider(state.volume, controller::setVolume, Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(thumbColor = PrimaryDark, activeTrackColor = PrimaryVariant))
                Text("${(state.volume * 100).roundToInt()}%", color = TextPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { controller.togglePause() }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight, contentColor = TextPrimary)) {
                    Text(if (state.playing || state.loading) "暂停" else if (state.error != null) "重试播放" else "继续播放")
                }
                OutlinedButton(onClick = { expanded = false; onChoose() }) { Text("更换声音", color = PrimaryDark) }
            }
        }
    }
}

private fun relaxTime(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
