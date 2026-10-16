package com.example.mydiary.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.ui.theme.*
import java.time.format.DateTimeFormatter

/**
 * 发现页面
 * 
 * 回顾个人经历：成长画像、时光信箱、情绪空间、高光时刻与小确幸。
 * - 高光时刻：展示用户标记为高光时刻的日记
 * - 小确幸：展示用户标记为小确幸的日记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onNavigateToGrowthPortrait: () -> Unit = {},
    onNavigateToHighlights: () -> Unit = {},
    onNavigateToLittleJoys: () -> Unit = {},
    onNavigateToDiary: (Long) -> Unit = {},
    viewModel: ExploreViewModel = hiltViewModel(),
    showTitle: Boolean = true
) {
    val memoryContext = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MintGreen.copy(alpha = 0.3f),
                        PaleYellow.copy(alpha = 0.3f),
                        SoftWhite
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 页面标题
            if (showTitle) item {
                Text(
                    text = "发现",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("留给自己的一片天地", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text("把此刻写给未来，让经历在房间里有个位置。", color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { memoryContext.startActivity(android.content.Intent(memoryContext, com.example.mydiary.memory.MemoryActivity::class.java).putExtra("page", "inbox")) }) { Text("时光信箱", color = TextPrimary) }
                            OutlinedButton(onClick = { memoryContext.startActivity(android.content.Intent(memoryContext, com.example.mydiary.memory.MemoryActivity::class.java).putExtra("page", "room")) }) { Text("情绪空间", color = TextPrimary) }
                        }
                    }
                }
            }
            item {
                ExploreSection(
                    title = "成长画像",
                    subtitle = "回看情绪变化、压力波动与积极经历",
                    icon = Icons.Outlined.ShowChart,
                    onClick = onNavigateToGrowthPortrait
                ) {
                    Text("在记录中慢慢理解自己", color = TextSecondary)
                }
            }

            // 高光时刻板块
            item {
                ExploreSection(
                    title = "高光时刻",
                    subtitle = "回顾你的精彩瞬间",
                    icon = Icons.Filled.Lightbulb,
                    iconTint = Color(0xFFFFD700),
                    onClick = onNavigateToHighlights
                ) {
                    HighlightsContent(
                        diaries = uiState.highlightDiaries,
                        isLoading = uiState.isLoading,
                        onDiaryClick = onNavigateToDiary
                    )
                }
            }
            
            // 小确幸板块
            item {
                ExploreSection(
                    title = "小确幸",
                    subtitle = "记录生活中的小幸福",
                    icon = Icons.Filled.Eco,
                    iconTint = Color(0xFF4CAF50),
                    onClick = onNavigateToLittleJoys
                ) {
                    LittleJoysContent(
                        diaries = uiState.littleJoyDiaries,
                        isLoading = uiState.isLoading,
                        onDiaryClick = onNavigateToDiary
                    )
                }
            }
            
            // 底部间距
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

/**
 * 发现页面板块组件
 */
@Composable
private fun ExploreSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = Primary,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "查看更多",
                    tint = TextTertiary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 内容区域
            content()
        }
    }
}

/**
 * 高光时刻内容
 */
@Composable
private fun HighlightsContent(
    diaries: List<Diary>,
    isLoading: Boolean,
    onDiaryClick: (Long) -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = Primary,
                modifier = Modifier.size(32.dp)
            )
        }
    } else if (diaries.isEmpty()) {
        // 空状态
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.2f),
                            PaleYellow.copy(alpha = 0.2f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = "高光时刻",
                    tint = Color(0xFFFFD700).copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "在日记编辑页点亮灯泡，记录高光时刻",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // 显示日记列表
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(diaries) { diary ->
                DiaryCard(
                    diary = diary,
                    tagColor = Color(0xFFFFD700),
                    onClick = { diary.id?.let { onDiaryClick(it) } }
                )
            }
        }
    }
}

/**
 * 小确幸内容
 */
@Composable
private fun LittleJoysContent(
    diaries: List<Diary>,
    isLoading: Boolean,
    onDiaryClick: (Long) -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = Primary,
                modifier = Modifier.size(32.dp)
            )
        }
    } else if (diaries.isEmpty()) {
        // 空状态
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PaleYellow.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Eco,
                    contentDescription = "小确幸",
                    tint = Color(0xFF4CAF50).copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "在日记编辑页点亮四叶草，记录小确幸",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // 显示日记列表
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(diaries) { diary ->
                DiaryCard(
                    diary = diary,
                    tagColor = Color(0xFF4CAF50),
                    onClick = { diary.id?.let { onDiaryClick(it) } }
                )
            }
        }
    }
}

/**
 * 日记卡片组件
 */
@Composable
private fun DiaryCard(
    diary: Diary,
    tagColor: Color,
    onClick: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MM/dd") }
    
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 日期和标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diary.date.format(dateFormatter),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tagColor)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 标题
            Text(
                text = diary.title ?: "无标题",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 内容预览
            val contentPreview = diary.mediaItems
                .filterIsInstance<MediaItem.Text>()
                .firstOrNull()?.content ?: ""
            
            Text(
                text = contentPreview.ifEmpty { "暂无内容" },
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
