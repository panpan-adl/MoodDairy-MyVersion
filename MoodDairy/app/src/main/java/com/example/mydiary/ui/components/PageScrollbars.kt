package com.example.mydiary.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private interface PageScrollSource {
    val scrolling: Boolean
    val canScroll: Boolean
    val fraction: ScrollbarFraction
    suspend fun moveTo(progress: Float)
}

private class ListScrollSource(val state: LazyListState) : PageScrollSource {
    override val scrolling get() = state.isScrollInProgress
    override val canScroll get() = state.canScrollBackward || state.canScrollForward
    override val fraction: ScrollbarFraction get() {
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.find { it.index == state.firstVisibleItemIndex }
        return lazyScrollbarFraction(layout.totalItemsCount, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
            (first?.size ?: 1) + layout.mainAxisItemSpacing,
            layout.visibleItemsInfo.map { ScrollbarItem(it.offset, it.size) },
            layout.viewportStartOffset, layout.viewportEndOffset,
            state.canScrollBackward, state.canScrollForward, layout.reverseLayout)
    }
    override suspend fun moveTo(progress: Float) {
        val layout = state.layoutInfo
        if (layout.totalItemsCount == 0) return
        val logical = if (layout.reverseLayout) 1f - progress else progress
        val position = logical * layout.totalItemsCount * (1f - fraction.visible)
        val index = if (logical >= 1f) layout.totalItemsCount - 1 else position.toInt().coerceIn(0, layout.totalItemsCount - 1)
        val meanSize = layout.visibleItemsInfo.map { it.size }.average().takeIf { it.isFinite() } ?: 0.0
        state.scrollToItem(index, if (logical >= 1f) 0 else ((position - index) * meanSize).toInt().coerceAtLeast(0))
    }
}

private class GridScrollSource(val state: LazyGridState) : PageScrollSource {
    override val scrolling get() = state.isScrollInProgress
    override val canScroll get() = state.canScrollBackward || state.canScrollForward
    private val columns get() = ((state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1).coerceAtLeast(1)
    override val fraction: ScrollbarFraction get() {
        val layout = state.layoutInfo
        val rows = layout.visibleItemsInfo.groupBy { it.row }.values.map { group ->
            ScrollbarItem(group.first().offset.y, group.maxOf { it.size.height })
        }
        val first = layout.visibleItemsInfo.find { it.index == state.firstVisibleItemIndex }
        return lazyScrollbarFraction(ceil(layout.totalItemsCount.toFloat() / columns).toInt(),
            state.firstVisibleItemIndex / columns, state.firstVisibleItemScrollOffset,
            (first?.size?.height ?: 1) + layout.mainAxisItemSpacing, rows,
            layout.viewportStartOffset, layout.viewportEndOffset,
            state.canScrollBackward, state.canScrollForward, layout.reverseLayout)
    }
    override suspend fun moveTo(progress: Float) {
        val layout = state.layoutInfo
        if (layout.totalItemsCount == 0) return
        val logical = if (layout.reverseLayout) 1f - progress else progress
        val rows = ceil(layout.totalItemsCount.toFloat() / columns).toInt()
        val row = (logical * rows * (1f - fraction.visible)).toInt()
        state.scrollToItem(if (logical >= 1f) layout.totalItemsCount - 1 else (row * columns).coerceIn(0, layout.totalItemsCount - 1))
    }
}

private class ColumnScrollSource(val state: ScrollState, val reverse: Boolean) : PageScrollSource {
    override val scrolling get() = state.isScrollInProgress
    override val canScroll get() = state.maxValue > 0 && state.maxValue < Int.MAX_VALUE && state.viewportSize > 0
    override val fraction: ScrollbarFraction get() {
        val value = state.value.toFloat() / state.maxValue.coerceAtLeast(1)
        return ScrollbarFraction(if (reverse) 1f - value else value,
            state.viewportSize.toFloat() / (state.viewportSize.toLong() + state.maxValue).coerceAtLeast(1))
    }
    override suspend fun moveTo(progress: Float) {
        state.scrollTo(((if (reverse) 1f - progress else progress) * state.maxValue).toInt())
    }
}

private class ScrollTarget(val source: PageScrollSource) {
    var bounds by mutableStateOf(Rect.Zero)
    var dragging by mutableStateOf(false)
}
private class ScrollbarRegistry(val window: Any) {
    var active by mutableStateOf<ScrollTarget?>(null)
}
private val LocalPageScrollbars = staticCompositionLocalOf<ScrollbarRegistry?> { null }

/** Draw once at the page's right edge, independent of each screen's content padding. */
@Composable
fun PageScrollbarHost(content: @Composable () -> Unit) {
    val view = LocalView.current.rootView
    val registry = remember(view) { ScrollbarRegistry(view) }
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    val target = registry.active
    val alpha = scrollbarAlpha(target)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { hostBounds = it.boundsInWindow() }) {
        CompositionLocalProvider(LocalPageScrollbars provides registry, content = content)
        Canvas(Modifier.matchParentSize()) {
            if (target != null && target.source.canScroll && alpha > 0f) {
                val top = (target.bounds.top - hostBounds.top).coerceIn(0f, size.height)
                val bottom = (target.bounds.bottom - hostBounds.top).coerceIn(top, size.height)
                drawPageThumb(target.source.fraction, alpha, top, bottom)
            }
        }
        if (target != null && target.source.canScroll && alpha > .1f) {
            val top = (target.bounds.top - hostBounds.top).coerceIn(0f, hostBounds.height)
            val bottom = (target.bounds.bottom - hostBounds.top).coerceIn(top, hostBounds.height)
            val inset = with(density) { 6.dp.toPx() }
            val trackHeight = (bottom - top - inset * 2).coerceAtLeast(0f)
            val thumb = scrollbarThumb(trackHeight, target.source.fraction, with(density) { 32.dp.toPx() })
            val hitTop = (top + inset + thumb.top - inset).coerceAtLeast(top)
            val hitBottom = (top + inset + thumb.top + thumb.height + inset).coerceAtMost(bottom)
            // Only the visible thumb has a hit target; the rest of the page stays interactive.
            Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, hitTop.roundToInt()) }
                .width(24.dp).height(with(density) { (hitBottom - hitTop).coerceAtLeast(0f).toDp() })
                .thumbDrag(target, alpha) { top - hitTop to bottom - hitTop })
        }
    }
}

@Composable
private fun scrollbarAlpha(target: ScrollTarget?): Float {
    var visible by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        snapshotFlow { (target.source.scrolling || target.dragging) && target.source.canScroll }.collectLatest { moving ->
            if (moving) visible = true else { delay(650); visible = false }
        }
    }
    return animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 100 else 250), label = "pageScrollbar").value
}

@Composable
private fun Modifier.pageScrollbar(source: PageScrollSource): Modifier {
    val registry = LocalPageScrollbars.current
    val view = LocalView.current.rootView
    val target = remember(source) { ScrollTarget(source) }
    // Dialogs use a different Android window; their indicator stays within that dialog.
    if (registry == null || registry.window !== view) {
        val alpha = scrollbarAlpha(target)
        return this.thumbDrag(target, alpha) { 0f to target.bounds.height }
            .onGloballyPositioned { target.bounds = it.boundsInWindow() }
            .drawWithContent {
                drawContent()
                if (source.canScroll && alpha > 0f) drawPageThumb(source.fraction, alpha, 0f, size.height)
            }
    }
    LaunchedEffect(target, registry) {
        snapshotFlow { source.scrolling }.collect { if (it && source.canScroll) registry.active = target }
    }
    DisposableEffect(target, registry) {
        onDispose { if (registry.active === target) registry.active = null }
    }
    return this.onGloballyPositioned { target.bounds = it.boundsInWindow() }
}

/** An idle transparent overlay never consumes taps or wheel events. */
@Composable
private fun Modifier.thumbDrag(target: ScrollTarget?, alpha: Float, track: () -> Pair<Float, Float>): Modifier {
    val scope = rememberCoroutineScope()
    val currentAlpha by rememberUpdatedState(alpha)
    val currentTrack by rememberUpdatedState(track)
    return pointerInput(target) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (target == null || currentAlpha < .1f || !target.source.canScroll || down.position.x < size.width - 24.dp.toPx()) return@awaitEachGesture
            val (top, bottom) = currentTrack()
            val height = bottom - top - 12.dp.toPx()
            if (height <= 0) return@awaitEachGesture
            val thumb = scrollbarThumb(height, target.source.fraction, 32.dp.toPx())
            val thumbTop = top + 6.dp.toPx() + thumb.top
            if (down.position.y < thumbTop - 8.dp.toPx() || down.position.y > thumbTop + thumb.height + 8.dp.toPx()) return@awaitEachGesture
            val grabOffset = down.position.y - thumbTop
            down.consume()
            target.dragging = true
            var moveJob: kotlinx.coroutines.Job? = null
            try {
                drag(down.id) { change ->
                    val fraction = ((change.position.y - top - 6.dp.toPx() - grabOffset) / (height - thumb.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    change.consume()
                    moveJob?.cancel()
                    moveJob = scope.launch { target.source.moveTo(fraction) }
                }
            } finally { target.dragging = false }
        }
    }
}

private fun DrawScope.drawPageThumb(fraction: ScrollbarFraction, alpha: Float, top: Float, bottom: Float) {
    val inset = 6.dp.toPx()
    val track = bottom - top - inset * 2
    if (track <= 0) return
    val thumb = scrollbarThumb(track, fraction, 32.dp.toPx())
    val width = 4.dp.toPx()
    val position = Offset(size.width - width - 3.dp.toPx(), top + inset + thumb.top)
    val thumbSize = Size(width, thumb.height)
    val radius = CornerRadius(width / 2)
    // A quiet outline keeps the pale white thumb legible over white cards.
    drawRoundRect(Color(0xFF7B9B90).copy(alpha = .25f * alpha), position, thumbSize, radius, style = Stroke(1.dp.toPx()))
    drawRoundRect(Color(0xFFF7FBF9).copy(alpha = .95f * alpha), position, thumbSize, radius)
}

@Composable
fun PageLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val source = remember(state) { ListScrollSource(state) }
    LazyColumn(modifier.pageScrollbar(source), state, contentPadding, reverseLayout,
        verticalArrangement, horizontalAlignment, flingBehavior, userScrollEnabled, content = content)
}

@Composable
fun Modifier.pageVerticalScroll(
    state: ScrollState, enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null, reverseScrolling: Boolean = false,
): Modifier {
    val source = remember(state, reverseScrolling) { ColumnScrollSource(state, reverseScrolling) }
    return pageScrollbar(source).verticalScroll(state, enabled, flingBehavior, reverseScrolling)
}

@Composable
fun PageLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    val source = remember(state) { GridScrollSource(state) }
    LazyVerticalGrid(columns, modifier.pageScrollbar(source), state, contentPadding, reverseLayout,
        verticalArrangement, horizontalArrangement, flingBehavior, userScrollEnabled, content = content)
}
