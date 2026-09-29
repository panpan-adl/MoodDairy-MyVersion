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
 * 小确幸详情页面
 * 治愈风格设计，展示用户标记为小确幸的所有日记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LittleJoysScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDiary: (Long) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // 治愈系绿色渐变
    val joyGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFE8F5E9).copy(alpha = 0.8f),
            Color(0xFFC8E6C9).copy(alpha = 0.5f),
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
                            imageVector = Icons.Filled.Eco,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "小确幸",
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
                .background(joyGradient)
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else if (uiState.littleJoyDiaries.isEmpty()) {
                EmptyLittleJoysState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    item { LittleJoyQuoteCard() }
                    
                    items(uiState.littleJoyDiaries) { diary ->
                        LittleJoyDiaryCard(
                            diary = diary,
                            onClick = { diary.id?.let { onNavigateToDiary(it) } }
                        )
                    }
                    
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LittleJoyQuoteCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("🍀 生活中的小美好", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("都是幸福的模样", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("那些微小却温暖的瞬间，值得被记住", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LittleJoyDiaryCard(diary: Diary, onClick: () -> Unit) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy年MM月dd日") }
    val weekFormatter = remember { DateTimeFormatter.ofPattern("EEEE") }
    
    val textContents = diary.mediaItems.filterIsInstance<MediaItem.Text>()
    val images = diary.mediaItems.filterIsInstance<MediaItem.Image>()
    val audios = diary.mediaItems.filterIsInstance<MediaItem.Audio>()
    
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(diary.date.format(dateFormatter), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(diary.date.format(weekFormatter), fontSize = 12.sp, color = TextSecondary)
                }
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF4CAF50).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Eco, "小确幸", tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Text(diary.title ?: "无标题", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            
            if (images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                if (images.size == 1) {
                    AsyncImage(
                        model = images[0].url,
                        contentDescription = "日记图片",
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images.take(5)) { image ->
                            AsyncImage(
                                model = image.url,
                                contentDescription = "日记图片",
                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (images.size > 5) {
                            item {
                                Box(
                                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF4CAF50).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+${images.size - 5}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                }
                            }
                        }
                    }
                }
            }
            
            if (textContents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                textContents.forEach { textItem ->
                    Text(textItem.content, fontSize = 14.sp, color = TextSecondary, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            
            if (audios.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Mic, "语音", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                    Text("${audios.size}条语音", fontSize = 12.sp, color = TextSecondary)
                }
            }
            
            diary.moodType?.let { mood ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                    Text("🍀 $mood", fontSize = 12.sp, color = Color(0xFF2E7D32), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyLittleJoysState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Eco, null, tint = Color(0xFF4CAF50).copy(alpha = 0.6f), modifier = Modifier.size(64.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("还没有小确幸", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            Text("在日记编辑页点亮四叶草🍀\n记录生活中的小美好", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 22.sp)
        }
    }
}
