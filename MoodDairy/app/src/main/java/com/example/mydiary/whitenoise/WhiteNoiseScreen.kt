package com.example.mydiary.whitenoise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.*

/**
 * 白噪音主界面 - 显示分类和搜索
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteNoiseScreen(
    onNavigateBack: () -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    if (selectedCategory != null) {
        // 显示分类详情页
        WhiteNoiseCategoryDetailScreen(
            category = selectedCategory!!,
            onNavigateBack = { selectedCategory = null }
        )
    } else {
        // 显示分类列表页
        WhiteNoiseCategoryListScreen(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onCategoryClick = { selectedCategory = it },
            onNavigateBack = onNavigateBack
        )
    }
}

/**
 * 分类列表界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhiteNoiseCategoryListScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val allSounds = remember { WhiteNoiseSoundList.getAllSounds() }
    
    // 分类信息
    val categories = remember {
        listOf(
            CategoryInfo("ANIMALS", "动物声音", "🐾", Color(0xFFFFB74D), 16),
            CategoryInfo("NATURE", "自然声音", "🌿", Color(0xFF81C784), 15),
            CategoryInfo("RAIN", "雨声", "🌧️", Color(0xFF64B5F6), 17),
            CategoryInfo("PLACES", "场所声音", "🏛️", Color(0xFF9575CD), 17),
            CategoryInfo("THINGS", "物品声音", "🎵", Color(0xFFFFB74D), 22),
            CategoryInfo("TRANSPORT", "交通工具", "✈️", Color(0xFF4FC3F7), 7),
            CategoryInfo("URBAN", "城市声音", "🏙️", Color(0xFF78909C), 7),
            CategoryInfo("NOISE", "噪音", "📻", Color(0xFFB0BEC5), 6)
        )
    }
    
    // 搜索过滤
    val filteredCategories = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            categories
        } else {
            val query = searchQuery.lowercase()
            categories.filter { 
                it.displayName.lowercase().contains(query) ||
                allSounds.any { sound -> 
                    sound.category == it.id && sound.name.lowercase().contains(query)
                }
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MintGreen.copy(alpha = 0.2f),
                        PaleYellow.copy(alpha = 0.2f),
                        SoftWhite
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = {
                    Text(
                        text = "白噪音",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            
            // 搜索框
            SearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            // 分类列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "选择分类 • 共${allSounds.size}个音频",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(filteredCategories) { category ->
                    CategoryCard(
                        category = category,
                        onClick = { onCategoryClick(category.id) }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

/**
 * 搜索框组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = "搜索分类或音频...",
                color = TextSecondary
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = TextSecondary
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = TextSecondary
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Surface,
            unfocusedContainerColor = Surface,
            focusedBorderColor = Primary,
            unfocusedBorderColor = Color.Transparent
        ),
        singleLine = true
    )
}

/**
 * 分类卡片
 */
@Composable
private fun CategoryCard(
    category: CategoryInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(category.color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.icon,
                    fontSize = 28.sp
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "${category.count}个音频",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
            
            // 箭头
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "进入",
                tint = TextTertiary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 分类详情界面 - 显示该分类下的所有音频
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhiteNoiseCategoryDetailScreen(
    category: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val whiteNoiseManager = remember { WhiteNoiseManager.getInstance() }
    
    // 获取该分类的音频
    val sounds = remember(category) {
        WhiteNoiseSoundList.getAllSounds().filter { it.category == category }
    }
    
    // 分类信息
    val categoryInfo = remember(category) {
        when (category) {
            "ANIMALS" -> CategoryInfo("ANIMALS", "动物声音", "🐾", Color(0xFFFFB74D), sounds.size)
            "NATURE" -> CategoryInfo("NATURE", "自然声音", "🌿", Color(0xFF81C784), sounds.size)
            "RAIN" -> CategoryInfo("RAIN", "雨声", "🌧️", Color(0xFF64B5F6), sounds.size)
            "PLACES" -> CategoryInfo("PLACES", "场所声音", "🏛️", Color(0xFF9575CD), sounds.size)
            "THINGS" -> CategoryInfo("THINGS", "物品声音", "🎵", Color(0xFFFFB74D), sounds.size)
            "TRANSPORT" -> CategoryInfo("TRANSPORT", "交通工具", "✈️", Color(0xFF4FC3F7), sounds.size)
            "URBAN" -> CategoryInfo("URBAN", "城市声音", "🏙️", Color(0xFF78909C), sounds.size)
            "NOISE" -> CategoryInfo("NOISE", "噪音", "📻", Color(0xFFB0BEC5), sounds.size)
            else -> CategoryInfo(category, category, "🎵", Primary, sounds.size)
        }
    }
    
    // 播放状态
    var playingStates by remember { mutableStateOf(mapOf<String, Boolean>()) }
    
    // 定期更新播放状态
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            playingStates = sounds.associate { it.id to whiteNoiseManager.isPlaying(it.id) }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        categoryInfo.color.copy(alpha = 0.15f),
                        SoftWhite
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = categoryInfo.displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${sounds.size}个音频",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            
            // 音频列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sounds) { sound ->
                    WhiteNoiseSoundCard(
                        sound = sound,
                        isPlaying = playingStates[sound.id] == true,
                        onPlayPause = {
                            if (playingStates[sound.id] == true) {
                                whiteNoiseManager.pauseSound(sound.id)
                            } else {
                                whiteNoiseManager.playSound(context, sound)
                            }
                        },
                        volume = whiteNoiseManager.getVolume(sound.id),
                        onVolumeChange = { volume ->
                            whiteNoiseManager.setVolume(sound.id, volume)
                        }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

/**
 * 白噪音声音卡片
 */
@Composable
private fun WhiteNoiseSoundCard(
    sound: WhiteNoiseSound,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    // 使用本地状态来跟踪音量，确保滑块响应
    var localVolume by remember(sound.id) { mutableStateOf(volume) }
    
    // 当外部音量变化时更新本地状态
    LaunchedEffect(volume) {
        localVolume = volume
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(sound.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sound.icon,
                        fontSize = 24.sp
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // 名称
                Text(
                    text = sound.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                
                // 播放/暂停按钮
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) sound.color else sound.color.copy(alpha = 0.3f)
                        )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White
                    )
                }
            }
            
            // 音量控制 - 始终显示，不管是否播放
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔉",
                    fontSize = 16.sp,
                    modifier = Modifier.alpha(if (isPlaying) 1f else 0.5f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Slider(
                    value = localVolume,
                    onValueChange = { newVolume ->
                        localVolume = newVolume
                        onVolumeChange(newVolume)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    colors = SliderDefaults.colors(
                        thumbColor = sound.color,
                        activeTrackColor = sound.color,
                        inactiveTrackColor = sound.color.copy(alpha = 0.3f),
                        disabledThumbColor = sound.color.copy(alpha = 0.5f),
                        disabledActiveTrackColor = sound.color.copy(alpha = 0.3f)
                    )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "🔊",
                    fontSize = 16.sp,
                    modifier = Modifier.alpha(if (isPlaying) 1f else 0.5f)
                )
            }
            
            // 显示当前音量百分比
            Text(
                text = "${(localVolume * 100).toInt()}%",
                fontSize = 12.sp,
                color = if (isPlaying) TextSecondary else TextTertiary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/**
 * 分类信息数据类
 */
private data class CategoryInfo(
    val id: String,
    val displayName: String,
    val icon: String,
    val color: Color,
    val count: Int
)
