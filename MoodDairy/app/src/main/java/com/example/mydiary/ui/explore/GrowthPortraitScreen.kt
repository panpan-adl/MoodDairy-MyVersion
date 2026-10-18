@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.mydiary.ui.explore

import com.example.mydiary.ui.components.PageLazyColumn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.models.PositiveEventPointResponse
import com.example.mydiary.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val MoodInk = Color(0xFF329A80)
private val StressInk = Color(0xFFC58D47)
private val PortraitBorder = Color(0xFFE6EFEB)
private val ShortDate = DateTimeFormatter.ofPattern("M/d")

@Composable
fun GrowthPortraitScreen(
    onNavigateBack: () -> Unit,
    viewModel: GrowthPortraitViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFFE0F6ED), Color(0xFFF5F9EE), SoftWhite)))) {
        Scaffold(containerColor = Color.Transparent, topBar = {
            TopAppBar(title = { Text("成长画像", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = { IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = TextPrimary)
                } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.portrait != null -> Column(Modifier.fillMaxSize()) {
                        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MoodInk)
                        if (state.error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("更新失败，显示上次结果", Modifier.weight(1f), color = TextSecondary, fontSize = 12.sp)
                            TextButton(onClick = { viewModel.loadPortrait(state.selectedDays) }) { Text("重试") }
                        }
                        GrowthPortraitContent(state.portrait!!, state.selectedDays, viewModel::loadPortrait)
                    }
                    state.isLoading -> Column(Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = MoodInk)
                        Text("正在整理你的成长足迹…", color = TextSecondary, fontSize = 13.sp)
                    }
                    state.error != null -> Column(Modifier.align(Alignment.Center).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.CloudOff, null, tint = MoodInk, modifier = Modifier.size(36.dp))
                        Text(state.error ?: "暂时没有加载出来", color = TextSecondary)
                        OutlinedButton(onClick = { viewModel.loadPortrait(state.selectedDays) }) { Text("重新加载") }
                    }
                    else -> state.portrait?.let { portrait ->
                        GrowthPortraitContent(portrait, state.selectedDays, viewModel::loadPortrait)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GrowthPortraitContent(portrait: GrowthPortraitResponse, selectedDays: Int, onSelectDays: (Int) -> Unit) {
    val emotion = remember(portrait.emotionCurve) { portraitDays(portrait.emotionCurve) }
    val stress = remember(portrait.stressCurve) { portraitDays(portrait.stressCurve) }
    PageLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "period") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .7f))
                .selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(7, 30, 90).forEach { days ->
                    val selected = days == selectedDays
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MoodInk else Color.Transparent)
                        .selectable(selected, role = Role.Tab, onClick = { onSelectDays(days) })
                        .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("最近${days}天", color = if (selected) Color.White else TextSecondary,
                            fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        item(key = "overview") { PortraitOverview(portrait, emotion.size) }
        item(key = "trend") {
            PortraitCard {
                SectionHeading("看见情绪的起伏", "情绪与压力 · 每日均值", Icons.Outlined.ShowChart)
                if (emotion.isEmpty() && stress.isEmpty()) {
                    PortraitEmpty("还没有足够的记录", "写下今天的一点感受，曲线就会从这里开始。")
                } else {
                    PortraitTrendChart(emotion, stress, portrait.rangeStart, portrait.rangeEnd)
                }
            }
        }
        item(key = "eventsHeading") {
            SectionHeading("那些值得记住的瞬间", "从记录中发现积极经历", Icons.Outlined.WbSunny,
                trailing = "${portrait.positiveEvents.size} 个")
        }
        if (portrait.positiveEvents.isEmpty()) item(key = "emptyEvents") {
            PortraitCard { PortraitEmpty("美好正在积累", "继续记录生活里的小事，值得记住的瞬间会慢慢浮现。") }
        }
        items(portrait.positiveEvents.take(5), key = { "event_${it.diaryId}" }) { event -> PositiveMoment(event) }
        item(key = "keywords") {
            PortraitCard {
                SectionHeading("你的成长关键词", "这些词，来自这一段时间的记录", Icons.Outlined.AutoAwesome)
                if (portrait.growthKeywords.isEmpty()) Text("关键词还在积累中。", color = TextSecondary, fontSize = 13.sp)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    portrait.growthKeywords.forEachIndexed { index, word ->
                        Text(word, color = if (index % 2 == 0) PrimaryDark else Color(0xFF92703F),
                            fontSize = if (index == 0) 17.sp else 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (index % 2 == 0) Color(0xFFEAF7F1) else Color(0xFFFBF4E7))
                                .padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        item(key = "sources") { PortraitSources(portrait.multimodalDistribution) }
        item(key = "closing") {
            Text("每一次记录，都是与自己的相遇。", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PortraitOverview(portrait: GrowthPortraitResponse, dayCount: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(0xFFCAEDDF), Color(0xFFEAF6DF))))
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("与自己同行", color = PrimaryDark, fontSize = 12.sp, letterSpacing = 2.sp)
                Text("看见自己的变化", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("${shortDate(portrait.rangeStart)} — ${shortDate(portrait.rangeEnd)}", color = PrimaryDark, fontSize = 12.sp)
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Spa, null, Modifier.size(30.dp), tint = MoodInk)
            }
        }
        Text("${portrait.emotionCurve.size} 篇记录  ·  陪伴自己 $dayCount 天", color = PrimaryDark, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .66f)).padding(vertical = 16.dp)) {
            PortraitStat("情绪均分", portraitAverage(portrait.emotionCurve)?.toString() ?: "—", MoodInk, Modifier.weight(1f))
            PortraitStat("压力均分", portraitAverage(portrait.stressCurve)?.toString() ?: "—", StressInk, Modifier.weight(1f))
            PortraitStat("积极瞬间", portrait.positiveEvents.size.toString(), PrimaryDark, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PortraitStat(label: String, value: String, ink: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, color = ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun PortraitCard(tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tint)
        .border(1.dp, PortraitBorder, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun SectionHeading(title: String, subtitle: String, icon: ImageVector, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF6EF)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MoodInk)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        }
        trailing?.let { Text(it, color = PrimaryDark, fontSize = 12.sp) }
    }
}

@Composable
private fun PortraitTrendChart(emotion: List<PortraitDay>, stress: List<PortraitDay>, rangeStart: String, rangeEnd: String) {
    val dates = remember(emotion, stress) { (emotion.map { it.date } + stress.map { it.date }).distinct().sorted() }
    var selected by remember(dates) { mutableStateOf(dates.lastOrNull()) }
    val start = runCatching { LocalDate.parse(rangeStart) }.getOrNull() ?: dates.first()
    val end = runCatching { LocalDate.parse(rangeEnd) }.getOrNull() ?: dates.last()
    val span = (end.toEpochDay() - start.toEpochDay()).coerceAtLeast(1).toFloat()
    val selectedMood = emotion.find { it.date == selected }
    val selectedStress = stress.find { it.date == selected }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ChartLegend("情绪", MoodInk)
        ChartLegend("压力", StressInk)
        Spacer(Modifier.weight(1f))
        Text("0—100 分", color = TextSecondary, fontSize = 10.sp)
    }
    val chartDescription = dates.joinToString("；") { day ->
        "${day}，情绪${emotion.find { it.date == day }?.value ?: "暂无"}，压力${stress.find { it.date == day }?.value ?: "暂无"}"
    }
    Row(Modifier.fillMaxWidth().height(160.dp)) {
        Column(Modifier.width(26.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("100", "50", "0").forEach { Text(it, color = TextSecondary, fontSize = 9.sp) }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = chartDescription }
            .pointerInput(dates, start, end) {
                detectTapGestures { tap ->
                    val ratio = ((tap.x - 8.dp.toPx()) / (size.width - 16.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val epoch = start.toEpochDay() + ratio * span
                    selected = dates.minByOrNull { abs(it.toEpochDay() - epoch) }
                }
            }) {
            val inset = 8.dp.toPx()
            val chartWidth = (size.width - inset * 2).coerceAtLeast(1f)
            val chartHeight = size.height - inset * 2
            fun x(date: LocalDate) = inset + ((date.toEpochDay() - start.toEpochDay()) / span).coerceIn(0f, 1f) * chartWidth
            fun y(value: Int) = inset + (1 - value / 100f) * chartHeight
            for (value in listOf(0, 25, 50, 75, 100)) {
                drawLine(PortraitBorder, Offset(inset, y(value)), Offset(size.width - inset, y(value)),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            selected?.let { date ->
                drawLine(MoodInk.copy(alpha = .18f), Offset(x(date), inset), Offset(x(date), size.height - inset), 1.dp.toPx())
            }
            fun series(points: List<PortraitDay>, color: Color, dashed: Boolean) {
                // Do not imply measured values across unrecorded days.
                val groups = mutableListOf<MutableList<PortraitDay>>()
                points.forEach { point ->
                    if (groups.isEmpty() || point.date.toEpochDay() - groups.last().last().date.toEpochDay() > 1)
                        groups.add(mutableListOf())
                    groups.last().add(point)
                }
                groups.forEach { group ->
                    if (group.size > 1) {
                        val line = Path().apply {
                            moveTo(x(group.first().date), y(group.first().value))
                            group.drop(1).forEach { lineTo(x(it.date), y(it.value)) }
                        }
                        if (!dashed) {
                            val fill = Path().apply {
                                addPath(line)
                                lineTo(x(group.last().date), size.height - inset)
                                lineTo(x(group.first().date), size.height - inset)
                                close()
                            }
                            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .16f), color.copy(alpha = .01f))))
                        }
                        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
                    }
                }
                points.forEach { point ->
                    val center = Offset(x(point.date), y(point.value))
                    if (point.date == selected) drawCircle(color.copy(alpha = .16f), 8.dp.toPx(), center)
                    drawCircle(Color.White, 4.dp.toPx(), center)
                    drawCircle(color, 2.7.dp.toPx(), center)
                }
            }
            series(stress, StressInk, true)
            series(emotion, MoodInk, false)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(start, start.plusDays((span / 2).toLong()), end).distinct().forEach {
            Text(it.format(ShortDate), fontSize = 10.sp, color = TextSecondary)
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F8F5)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(selected?.format(ShortDate).orEmpty(), fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("${selectedMood?.count ?: selectedStress?.count ?: 0} 篇记录", fontSize = 10.sp, color = TextSecondary)
        }
        Text("情绪 ${selectedMood?.value ?: "—"}", fontSize = 13.sp, color = MoodInk, fontWeight = FontWeight.Medium)
        Text("压力 ${selectedStress?.value ?: "—"}", fontSize = 13.sp, color = StressInk, fontWeight = FontWeight.Medium)
    }
    Text("轻点曲线查看当天数值 · 空白日期暂无记录", color = TextSecondary, fontSize = 10.sp)
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun PositiveMoment(event: PositiveEventPointResponse) {
    var expanded by remember(event.diaryId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
        .border(1.dp, PortraitBorder, RoundedCornerShape(18.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(42.dp).padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.WbSunny, null, Modifier.size(22.dp), tint = StressInk)
            Text(shortDate(event.date), color = StressInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            Text(event.summary, color = TextSecondary, fontSize = 12.sp, lineHeight = 20.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            if (event.title.length > 30 || event.summary.length > 60) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(if (expanded) "收起内容" else "展开内容", color = PrimaryDark, fontSize = 11.sp)
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, Modifier.size(16.dp), tint = PrimaryDark)
                }
            }
        }
    }
}

@Composable
private fun PortraitSources(distribution: Map<String, Int>) {
    val sources = listOf("text" to "文字", "voice" to "语音", "image" to "图片", "video" to "视频", "drawing" to "绘画")
    val colors = listOf(MoodInk, Color(0xFF85AFB4), StressInk, Color(0xFFAE9AB9), Color(0xFF95AC67))
    val maximum = (distribution.values.maxOrNull() ?: 0).coerceAtLeast(1)
    PortraitCard {
        SectionHeading("记录的不同方式", "每一种表达，都留下了线索", Icons.Outlined.DonutSmall)
        sources.forEachIndexed { index, (key, name) ->
            val count = (distribution[key] ?: 0).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(28.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Color(0xFFF0F4F2))) {
                    if (count > 0) Box(Modifier.fillMaxWidth((count.toFloat() / maximum).coerceIn(0f, 1f))
                        .fillMaxHeight().clip(CircleShape).background(colors[index]))
                }
                Text("$count 篇", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.widthIn(min = 32.dp))
            }
        }
        Text("同一篇记录可以包含多种表达方式。", fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun PortraitEmpty(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Eco, null, Modifier.size(30.dp), tint = MoodInk.copy(alpha = .6f))
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(message, color = TextSecondary, fontSize = 12.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun shortDate(value: String): String = runCatching { LocalDate.parse(value).format(ShortDate) }.getOrDefault(value)
