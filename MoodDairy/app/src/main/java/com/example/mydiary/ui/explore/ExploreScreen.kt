package com.example.mydiary.ui.explore

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.memory.MemoryActivity
import com.example.mydiary.ui.components.PageLazyColumn
import com.example.mydiary.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val ReviewGreen = Color(0xFF268560)
private val ReviewGold = Color(0xFFAB7C37)
private val ReviewPaper = Color(0xFFF7FAF5)

@Composable
fun ExploreScreen(
    onNavigateToGrowthPortrait: () -> Unit = {},
    onNavigateToHighlights: () -> Unit = {},
    onNavigateToLittleJoys: () -> Unit = {},
    onNavigateToDiary: (Long) -> Unit = {},
    viewModel: ExploreViewModel = hiltViewModel(),
    showTitle: Boolean = true,
) {
    val portraitState by viewModel.portrait.collectAsStateWithLifecycle()
    val portrait = (portraitState as? ApiResult.Success)?.data
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refresh() }
    PageLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(ReviewGreen, CircleShape))
                    Text("  慢慢记录 · 慢慢看见", color = ReviewGreen, fontSize = 11.sp,
                        letterSpacing = 2.sp)
                }
                Text(if (showTitle) "回顾，与你有关的时光" else "看见，自己的变化", color = TextPrimary,
                    fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("回望情绪与经历，慢慢读懂这一程的自己。", color = TextSecondary, fontSize = 13.sp)
            }
        }
        item {
            PortraitPreview(portrait, portraitState is ApiResult.Loading,
                portraitState is ApiResult.Error, onNavigateToGrowthPortrait)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("值得珍藏的", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("生活里的两种光", color = TextSecondary, fontSize = 11.sp)
                }
                MemoryCollection("高光时刻", "那些闪闪发亮的自己", Icons.Outlined.WbSunny,
                    ReviewGold, Color(0xFFFFF5DD), onNavigateToHighlights)
                MemoryCollection("小确幸", "平凡日子里，悄悄长出的甜", Icons.Outlined.Eco,
                    ReviewGreen, Color(0xFFEAF4E9), onNavigateToLittleJoys)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("留一点空间，给自己", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IllustratedMemoryDoor(mailbox = false, modifier = Modifier.weight(1f)) {
                        context.startActivity(Intent(context, MemoryActivity::class.java).putExtra("page", "room"))
                    }
                    IllustratedMemoryDoor(mailbox = true, modifier = Modifier.weight(1f)) {
                        context.startActivity(Intent(context, MemoryActivity::class.java).putExtra("page", "inbox"))
                    }
                }
            }
        }
        item {
            Text("每一次回望，都是和自己重逢。", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = TextTertiary, fontSize = 11.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun PortraitPreview(portrait: GrowthPortraitResponse?, loading: Boolean, failed: Boolean, onClick: () -> Unit) {
    val mood = portrait?.let { portraitAverage(it.emotionCurve) }
    val stress = portrait?.let { portraitAverage(it.stressCurve) }
    val dayCount = portraitDays(portrait?.emotionCurve.orEmpty()).size
    val dateFormat = remember { DateTimeFormatter.ofPattern("M.dd") }
    val range = portrait?.let {
        runCatching { "${LocalDate.parse(it.rangeStart).format(dateFormat)} — ${LocalDate.parse(it.rangeEnd).format(dateFormat)}" }.getOrNull()
    }
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFB9DFCB)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(
            listOf(Color(0xFFD1EEDF), Color(0xFFE5F2D9)))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).background(Color(0xFFCFF5E4), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Insights, null, tint = ReviewGreen, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("成长画像", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("情绪 · 压力 · 积极经历", fontSize = 11.sp, color = TextSecondary)
                }
                Surface(color = Color(0xFFE2F8DF), shape = RoundedCornerShape(50)) {
                    Text("近 30 天", color = ReviewGreen, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("这段时间的你", Modifier.weight(1f), color = ReviewGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(range ?: "最近 30 天", color = TextSecondary, fontSize = 10.sp)
                }
                when {
                    portrait != null -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            PortraitPreviewMetric("情绪均分", mood, Color(0xFF269B6D), Modifier.weight(1f),
                                barColor = Color(0xFF69D5AC))
                            PortraitPreviewMetric("压力均分", stress, ReviewGold, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                            .background(Color.White.copy(alpha = .65f)).padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.WbSunny, null, tint = ReviewGold, modifier = Modifier.size(21.dp))
                            Text("${portrait.positiveEvents.size} 个积极瞬间", Modifier.weight(1f),
                                color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("$dayCount 天记录", color = TextSecondary, fontSize = 10.sp)
                        }
                        if (portrait.growthKeywords.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                portrait.growthKeywords.take(3).forEach { word ->
                                    Text(word, modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                        .background(Color.White.copy(alpha = .6f)).padding(horizontal = 8.dp, vertical = 5.dp),
                                        color = ReviewGreen, fontSize = 11.sp, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                    }
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = ReviewGreen, strokeWidth = 2.dp)
                        Text("正在整理你的成长画像…", fontSize = 12.sp, color = TextSecondary)
                    }
                    else -> Text(if (failed) "画像暂未加载，点此重新查看" else "从一次记录开始，慢慢看见自己的变化。",
                        color = TextSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }

        }
    }
}

@Composable
private fun PortraitPreviewMetric(label: String, value: Int?, ink: Color, modifier: Modifier,
    barColor: Color = ink.copy(alpha = .65f)) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value?.toString() ?: "—", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text("/ 100", color = TextTertiary, fontSize = 9.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        LinearProgressIndicator(progress = { (value ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp)
            .clip(RoundedCornerShape(50)), color = barColor, trackColor = Color.White.copy(alpha = .8f),
            gapSize = 0.dp, drawStopIndicator = {})
    }
}
@Composable
private fun MemoryCollection(title: String, subtitle: String, icon: ImageVector, ink: Color,
    paper: Color, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(paper)
        .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(Color.White.copy(alpha = .65f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = ink, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.ChevronRight, "查看全部$title", tint = ink, modifier = Modifier.size(20.dp))
    }
}




