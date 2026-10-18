package com.example.mydiary.ui.explore

import com.example.mydiary.ui.components.PageLazyColumn

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.ui.theme.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.BorderStroke

/**
 * 高光时刻详情页面
 * 治愈风格设计，展示用户标记为高光时刻的所有日记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDiary: (Long) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // 沿用生活页的薄荷绿、淡黄绿与柔白背景
    val highlightGradient = Brush.verticalGradient(
        colors = listOf(
            MintGreen.copy(alpha = 0.3f),
            PaleYellow.copy(alpha = 0.3f),
            SoftWhite
        )
    )
    
    Box(Modifier.fillMaxSize().background(highlightGradient)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "高光时刻",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    
                    .padding(paddingValues)
            ) {
                if (uiState.isLoadingHighlights) {
                    // 加载状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryDark,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else if (uiState.highlightDiaries.isEmpty()) {
                    // 空状态 - 治愈风格
                    EmptyHighlightsState()
                } else {
                    // 日记列表
                    PageLazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        // 顶部励志语
                        item {
                            HighlightQuoteCard(uiState.highlightDiaries.size)
                        }
                        
                        // 日记列表
                        items(uiState.highlightDiaries) { diary ->
                            HighlightDiaryCard(
                                diary = diary,
                                onClick = { diary.id?.let { onNavigateToDiary(it) } }
                            )
                        }
                        
                        // 底部间距
                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 高光时刻励志语卡片
 */
@Composable
private fun HighlightQuoteCard(count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(SecondaryLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = SecondaryDark, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("把闪光的日子，慢慢收藏", fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("已珍藏 $count 篇高光日记", fontSize = 13.sp, color = PrimaryDark)
            }
        }
    }
}
/**
 * 高光时刻日记卡片：图片与摘要，点击后阅读全文
 */
@Composable
private fun HighlightDiaryCard(
    diary: Diary,
    onClick: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy年M月d日") }
    val weekFormatter = remember { DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE) }
    
    // 提取文字内容
    val textContents = diary.mediaItems.filterIsInstance<MediaItem.Text>()
    // 提取图片
    val images = diary.mediaItems.filterIsInstance<MediaItem.Image>()
    // 提取音频
    val audios = diary.mediaItems.filterIsInstance<MediaItem.Audio>()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, SurfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 日期和高光标记
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = diary.date.format(dateFormatter),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = diary.date.format(weekFormatter),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                
                // 高光标记
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SecondaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lightbulb,
                        contentDescription = "高光时刻",
                        tint = PrimaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 标题
            Text(
                text = diary.title ?: "无标题",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // 图片展示区域
            if (images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                if (images.size == 1) {
                    // 单张图片 - 大图展示
                    AsyncImage(
                        model = images[0].url,
                        contentDescription = "日记图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.65f)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 多张图片 - 横向滚动
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(images.take(5)) { image ->
                            AsyncImage(
                                model = image.url,
                                contentDescription = "日记图片",
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // 如果超过5张，显示更多提示
                        if (images.size > 5) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SecondaryLight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${images.size - 5}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PrimaryDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // 文字内容展示
            if (textContents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = textContents.map { it.content.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 23.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 音频标记
            if (audios.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "语音",
                        tint = PrimaryDark,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${audios.size}条语音",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            
            // 情绪标签（如果有）
            diary.moodType?.let { mood ->
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PrimaryLight.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = mood,
                        fontSize = 12.sp,
                        color = PrimaryDark,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = SurfaceVariant)
            Text(
                text = "重温这一天  →",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryDark,
                modifier = Modifier.align(Alignment.End).padding(top = 12.dp)
            )
        }
    }
}

/**
 * 空状态 - 高光时刻
 */
@Composable
private fun EmptyHighlightsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // 大灯泡图标
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(PrimaryLight.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = PrimaryDark.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "还没有高光时刻",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "在日记中点亮小灯泡\n让值得珍藏的一天，留在这里",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}


