package com.example.mydiary.ui.explore

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    
    // 治愈系金色渐变
    val highlightGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFF8E1).copy(alpha = 0.8f),  // 暖黄色
            Color(0xFFFFECB3).copy(alpha = 0.5f),  // 淡金色
            SoftWhite
        )
    )
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lightbulb,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "高光时刻",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
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
                .background(highlightGradient)
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                // 加载状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFFD700),
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else if (uiState.highlightDiaries.isEmpty()) {
                // 空状态 - 治愈风格
                EmptyHighlightsState()
            } else {
                // 日记列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // 顶部励志语
                    item {
                        HighlightQuoteCard()
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

/**
 * 高光时刻励志语卡片
 */
@Composable
private fun HighlightQuoteCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "✨ 每一个闪光的瞬间",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Text(
                text = "都值得被珍藏",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "记录生活中那些让你骄傲的时刻",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 高光时刻日记卡片 - 详细展示内容和图片
 */
@Composable
private fun HighlightDiaryCard(
    diary: Diary,
    onClick: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy年MM月dd日") }
    val weekFormatter = remember { DateTimeFormatter.ofPattern("EEEE") }
    
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        .background(Color(0xFFFFD700).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lightbulb,
                        contentDescription = "高光时刻",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 标题
            Text(
                text = diary.title ?: "无标题",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
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
                            .height(200.dp)
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
                                        .background(Color(0xFFFFD700).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${images.size - 5}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFD700)
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
                
                textContents.forEach { textItem ->
                    Text(
                        text = textItem.content,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
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
                        tint = Color(0xFFFFD700),
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
                    color = Color(0xFFFFD700).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "💫 $mood",
                        fontSize = 12.sp,
                        color = Color(0xFFE6A700),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
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
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFD700).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = Color(0xFFFFD700).copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "还没有高光时刻",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "在日记编辑页点亮灯泡💡\n记录你的精彩瞬间",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}
