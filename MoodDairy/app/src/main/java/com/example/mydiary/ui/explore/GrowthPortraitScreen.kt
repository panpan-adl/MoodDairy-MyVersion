@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.mydiary.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.models.GrowthTrendPointResponse
import com.example.mydiary.data.models.PositiveEventPointResponse
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary

@Composable
fun GrowthPortraitScreen(
    onNavigateBack: () -> Unit,
    viewModel: GrowthPortraitViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Insights,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("成长画像", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(MintGreen.copy(alpha = 0.28f), PaleYellow.copy(alpha = 0.28f), SoftWhite),
                    ),
                )
                .padding(paddingValues),
        ) {
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                }

                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(uiState.error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    }
                }

                else -> {
                    val portrait = uiState.portrait
                    if (portrait != null) {
                        GrowthPortraitContent(
                            portrait = portrait,
                            selectedDays = uiState.selectedDays,
                            onSelectDays = viewModel::loadPortrait,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GrowthPortraitContent(
    portrait: GrowthPortraitResponse,
    selectedDays: Int,
    onSelectDays: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("多模态情绪画像", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${portrait.rangeStart} 至 ${portrait.rangeEnd}",
                        fontSize = 13.sp,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(portrait.summary, color = TextPrimary, lineHeight = 22.sp)
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(7, 30, 90)) { days ->
                    FilterChip(
                        selected = selectedDays == days,
                        onClick = { onSelectDays(days) },
                        label = { Text("最近${days}天") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MintGreen,
                            selectedLabelColor = TextPrimary,
                        ),
                    )
                }
            }
        }

        item {
            PortraitSectionCard(title = "情绪曲线", icon = Icons.Filled.ShowChart) {
                TrendList(points = portrait.emotionCurve, barColor = Color(0xFF4CAF50))
            }
        }

        item {
            PortraitSectionCard(title = "压力波动", icon = Icons.Filled.Psychology) {
                TrendList(points = portrait.stressCurve, barColor = Color(0xFFFFB74D))
            }
        }

        item {
            PortraitSectionCard(title = "积极事件", icon = Icons.Filled.Favorite) {
                if (portrait.positiveEvents.isEmpty()) {
                    Text("暂未识别到明显的积极事件高峰，继续记录会更准确。", color = TextSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        portrait.positiveEvents.take(5).forEach { event ->
                            PositiveEventRow(event)
                        }
                    }
                }
            }
        }

        item {
            PortraitSectionCard(title = "成长关键词", icon = Icons.Filled.Insights) {
                if (portrait.growthKeywords.isEmpty()) {
                    Text("关键词仍在积累中。", color = TextSecondary)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(portrait.growthKeywords) { keyword ->
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(keyword) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PaleYellow,
                                    selectedLabelColor = TextPrimary,
                                ),
                            )
                        }
                    }
                }
            }
        }

        item {
            PortraitSectionCard(title = "自我认知反馈", icon = Icons.Filled.Psychology) {
                Text(portrait.selfAwarenessFeedback, color = TextPrimary, lineHeight = 22.sp)
            }
        }

        item {
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun PortraitSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            content()
        }
    }
}

@Composable
private fun TrendList(
    points: List<GrowthTrendPointResponse>,
    barColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        points.takeLast(8).forEach { point ->
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(point.date, color = TextSecondary, fontSize = 13.sp)
                    Text("${point.value}", color = TextPrimary, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(Color.White, RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(point.value / 100f)
                            .height(10.dp)
                            .background(barColor, RoundedCornerShape(999.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PositiveEventRow(event: PositiveEventPointResponse) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SoftWhite),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.title, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text("${event.score}", color = Primary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(event.date, color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(event.summary, color = TextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
