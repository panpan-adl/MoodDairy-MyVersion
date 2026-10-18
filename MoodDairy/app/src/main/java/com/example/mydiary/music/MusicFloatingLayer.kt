package com.example.mydiary.music

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val density = LocalDensity.current
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    if (state.trackId == null) { return }
    // Root activity owns this layer, so changing destinations does not restart audio.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(bottom = 92.dp)) {
        // Include room for the close badge so it stays reachable at screen edges.
        val bubbleSize = 72.dp
        val availableX = with(density) { (maxWidth - bubbleSize).toPx() }
        val availableY = with(density) { (maxHeight - bubbleSize).toPx() }
        val x = clampFloatingPosition(dragX, availableX)
        val y = clampFloatingPosition(if (dragY.isNaN()) availableY - with(density) { 240.dp.toPx() } else dragY, availableY)
        Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(bubbleSize).hoverable(hoverSource)) {
        Surface(Modifier.fillMaxSize().padding(6.dp)
            .semantics {
                contentDescription = "音乐悬浮球：${state.title}，可拖动"
                stateDescription = when {
                    state.error != null -> "播放失败"
                    state.loading -> "正在准备声音"
                    state.playing -> "播放中"
                    else -> "已暂停"
                }
            }
            .pointerInput(availableX, availableY) {
                detectDragGestures(
                    onDragStart = {
                        dragX = clampFloatingPosition(dragX, availableX)
                        dragY = clampFloatingPosition(
                            if (dragY.isNaN()) availableY - with(density) { 240.dp.toPx() } else dragY,
                            availableY,
                        )
                    },
                ) { change, amount ->
                    change.consume()
                    dragX = clampFloatingPosition(dragX + amount.x, availableX)
                    dragY = clampFloatingPosition(dragY + amount.y, availableY)
                }
            }
            .clickable(onClickLabel = "展开播放控制") { expanded = true }, shape = CircleShape,
            color = Color.White, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryLight)) {
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(PrimaryLight, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (state.whiteNoise) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                        contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(26.dp))
                }
                if (state.loading) {
                    CircularProgressIndicator(Modifier.fillMaxSize(), color = PrimaryDark, strokeWidth = 2.dp)
                } else {
                    Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(PrimaryDark, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(when { state.error != null -> Icons.Default.ErrorOutline
                            state.playing -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow },
                            contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        if (hovered) {
            Box(
                Modifier.align(Alignment.TopEnd).size(24.dp)
                    .background(Color.White, CircleShape).border(1.dp, Color(0xFFDCEBE3), CircleShape)
                    .clickable(onClickLabel = "停止播放并关闭悬浮球") {
                        controller.stop()
                        expanded = false
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "停止播放并关闭悬浮球",
                    tint = PrimaryDark, modifier = Modifier.size(15.dp))
            }
        }
        }
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftWhite,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(32.dp, 4.dp).background(PrimaryDark.copy(alpha = .2f), CircleShape)) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .background(Brush.verticalGradient(listOf(Color(0xFFEAF7F0), SoftWhite)))
                .padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("放松时刻", color = PrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Surface(color = Color.White.copy(alpha = .8f), shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(5.dp).background(PrimaryVariant, CircleShape))
                        Text(when { state.error != null -> "等待重试"; state.loading -> "准备中"; state.playing -> "正在播放"; else -> "已暂停" },
                            fontSize = 11.sp, color = PrimaryDark)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(112.dp).background(Brush.linearGradient(listOf(Color(0xFF476F62), Color(0xFF244B42))), CircleShape)
                .border(5.dp, Color.White.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
                for (ring in listOf(88, 72, 56)) {
                    Box(Modifier.size(ring.dp).border(1.dp, Color.White.copy(alpha = .12f), CircleShape))
                }
                Box(Modifier.size(40.dp).background(PrimaryLight, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (state.whiteNoise) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                        contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(state.title, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold,
                color = TextPrimary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(if (state.whiteNoise) "白噪音 · 循环陪伴" else state.artist.ifBlank { "给自己一段安静的时间" },
                fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            if (!state.whiteNoise && state.durationMs > 0) {
                MusicControlSlider(value = state.positionMs.coerceIn(0, state.durationMs).toFloat(),
                    range = 0f..state.durationMs.toFloat(), label = "播放进度", onChange = { controller.seekTo(it.toLong()) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(relaxTime(state.positionMs), color = TextSecondary, fontSize = 11.sp)
                    Text(relaxTime(state.durationMs), color = TextSecondary, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { expanded = false; onChoose() }) {
                        Icon(Icons.Default.QueueMusic, "更换声音", tint = PrimaryDark)
                    }
                    Text("更换声音", fontSize = 11.sp, color = TextSecondary)
                }
                FilledIconButton(onClick = { controller.togglePause() }, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PrimaryDark, contentColor = Color.White)) {
                    Icon(when { state.error != null -> Icons.Default.Refresh
                        state.playing || state.loading -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow },
                        contentDescription = if (state.playing || state.loading) "暂停" else if (state.error != null) "重试播放" else "继续播放",
                        modifier = Modifier.size(32.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { expanded = false }) { Icon(Icons.Default.KeyboardArrowDown, "收起播放器", tint = PrimaryDark) }
                    Text("收起", fontSize = 11.sp, color = TextSecondary)
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .85f),
                border = BorderStroke(1.dp, Color(0xFFE7F0EB))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, "音量", tint = PrimaryDark, modifier = Modifier.size(20.dp))
                    MusicControlSlider(state.volume, onChange = controller::setVolume, label = "音量",
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text("${(state.volume * 100).roundToInt()}%", color = PrimaryDark, fontSize = 12.sp,
                        modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { controller.stop(); expanded = false },
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("结束聆听", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicControlSlider(value: Float, onChange: (Float) -> Unit, label: String,
    modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..1f) {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Slider(value = value, onValueChange = onChange, valueRange = range,
        modifier = modifier.semantics { contentDescription = label },
        thumb = { Box(Modifier.size(14.dp).background(PrimaryDark, CircleShape).border(2.dp, Color.White, CircleShape)) },
        track = {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFDCEBE3), CircleShape)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(PrimaryVariant, CircleShape))
            }
        },
    )
}

private fun relaxTime(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
