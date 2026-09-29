package com.example.mydiary.ui.achievement

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.Achievement
import com.example.mydiary.data.models.AchievementStatus
import com.example.mydiary.data.repository.AchievementRepository
import com.example.mydiary.data.models.ShopCategory
import com.example.mydiary.data.models.ShopItem
import com.example.mydiary.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementScreen(
    onNavigateBack: () -> Unit,
    onNavigateToShop: () -> Unit,
    viewModel: AchievementViewModel = hiltViewModel()
) {
    val achievements by viewModel.achievements.collectAsState()
    val userPoints by viewModel.userPoints.collectAsState()
    val newlyUnlocked by viewModel.newlyUnlockedAchievements.collectAsState()
    val lastCheckIn by viewModel.lastCheckInDate.collectAsState()

    var selectedAchievement by remember { mutableStateOf<Achievement?>(null) }

    // 弹出新成就解锁对话框
    if (newlyUnlocked.isNotEmpty()) {
        AchievementUnlockDialog(
            achievement = newlyUnlocked.first(),
            onDismiss = { viewModel.clearNewlyUnlockedAchievements() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "成就与积分",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.3f),
                            PaleYellow.copy(alpha = 0.3f),
                            SoftWhite,
                        ),
                    ),
                )
                .padding(paddingValues)
        ) {
            // 积分卡片
            PointsCard(
                totalPoints = userPoints.totalPoints,
                availablePoints = userPoints.availablePoints,
                onNavigateToShop = onNavigateToShop
            )

            // 签到区块
            CheckInCard(
                lastCheckInDate = lastCheckIn,
                onCheckIn = { viewModel.checkIn() }
            )

            // 成就列表（仅成就，全部物品在商城中）
            AchievementGrid(
                achievements = achievements,
                onAchievementClick = { selectedAchievement = it }
            )
        }
    }

    // 成就详情弹窗：放大图标 + 实现进度
    selectedAchievement?.let { achievement ->
        AchievementDetailDialog(
            achievement = achievement,
            onDismiss = { selectedAchievement = null }
        )
    }
}

@Composable
private fun PointsCard(
    totalPoints: Int,
    availablePoints: Int,
    onNavigateToShop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Primary.copy(alpha = 0.8f),
                            MintGreen.copy(alpha = 0.6f)
                        )
                    )
                )
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "我的积分",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$availablePoints",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "累计获得: $totalPoints",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Button(
                onClick = onNavigateToShop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.ShoppingBag, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("去商城", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CheckInCard(
    lastCheckInDate: Long?,
    onCheckIn: () -> Unit
) {
    val alreadyCheckedIn = lastCheckInDate != null && run {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = lastCheckInDate }
        val today = java.util.Calendar.getInstance()
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CalendarToday,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "每日签到",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (alreadyCheckedIn) "今日已签到" else "签到送积分",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            if (alreadyCheckedIn) {
                Text(
                    text = "已签到",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .background(MintGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            } else {
                Button(
                    onClick = onCheckIn,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("签到 +${AchievementRepository.DAILY_CHECKIN_POINTS}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AchievementGrid(
    achievements: List<Achievement>,
    onAchievementClick: (Achievement) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(achievements) { achievement ->
            AchievementCard(achievement = achievement, onClick = { onAchievementClick(achievement) })
        }
    }
}

@Composable
private fun AchievementCard(achievement: Achievement, onClick: () -> Unit = {}) {
    val isUnlocked = achievement.status == AchievementStatus.UNLOCKED
    val progress = (achievement.currentProgress.toFloat() / achievement.requirement).coerceIn(0f, 1f)

    val scale by animateFloatAsState(
        targetValue = if (isUnlocked) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked)
                MintGreen.copy(alpha = 0.2f)
            else
                Surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isUnlocked) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (isUnlocked)
                            Brush.linearGradient(listOf(Primary, MintGreen))
                        else
                            Brush.linearGradient(
                                listOf(
                                    Color.Gray.copy(alpha = 0.3f),
                                    Color.Gray.copy(alpha = 0.5f)
                                )
                            )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getAchievementIcon(achievement.iconName),
                    contentDescription = null,
                    tint = if (isUnlocked) Color.White else Color.Gray,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = achievement.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isUnlocked) TextPrimary else TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = achievement.description,
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isUnlocked) Primary else Color.Gray.copy(alpha = 0.3f),
                    trackColor = Color.Gray.copy(alpha = 0.2f),
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${achievement.currentProgress}/${achievement.requirement}",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                    if (achievement.pointsReward > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "+${achievement.pointsReward}",
                                fontSize = 10.sp,
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShopGrid(shopItems: List<ShopItem>, onPurchase: (ShopItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(shopItems) { item ->
            ShopItemCard(item = item, onPurchase = { onPurchase(item) })
        }
    }
}

@Composable
internal fun ShopItemCard(
    item: ShopItem,
    onPurchase: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            getShopCategoryColors(item.category)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getShopItemIcon(item.iconName),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.description,
                fontSize = 10.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onPurchase,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${item.price}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AchievementDetailDialog(
    achievement: Achievement,
    onDismiss: () -> Unit
) {
    val isUnlocked = achievement.status == AchievementStatus.UNLOCKED
    val progress = (achievement.currentProgress.toFloat() / achievement.requirement).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = RoundedCornerShape(24.dp),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 放大的成就图标
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            if (isUnlocked)
                                Brush.linearGradient(listOf(Primary, MintGreen))
                            else
                                Brush.linearGradient(
                                    listOf(
                                        Color.Gray.copy(alpha = 0.3f),
                                        Color.Gray.copy(alpha = 0.5f)
                                    )
                                )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getAchievementIcon(achievement.iconName),
                        contentDescription = null,
                        tint = if (isUnlocked) Color.White else Color.Gray,
                        modifier = Modifier.size(50.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = achievement.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = achievement.description,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                // 实现进度
                Text(
                    text = "实现进度",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (isUnlocked) Primary else Color.Gray.copy(alpha = 0.5f),
                    trackColor = Color.Gray.copy(alpha = 0.2f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${achievement.currentProgress} / ${achievement.requirement}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
                if (achievement.pointsReward > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFFFFF8E1), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "奖励 +${achievement.pointsReward} 积分",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8F00)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun AchievementUnlockDialog(
    achievement: Achievement,
    onDismiss: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dialogScale"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
        containerColor = Surface,
        shape = RoundedCornerShape(24.dp),
        confirmButton = {},
        text = {
            Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 庆祝动画图标
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Primary, MintGreen))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "🎉 成就解锁!",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = achievement.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = achievement.description,
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            if (achievement.pointsReward > 0) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            Color(0xFFFFF8E1),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+${achievement.pointsReward} 积分",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF8F00)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("太棒了!", fontWeight = FontWeight.Bold)
            }
        }
    })
}

// 图标映射函数
private fun getAchievementIcon(iconName: String): ImageVector {
    return when (iconName) {
        "edit_note" -> Icons.Filled.EditNote
        "event_note" -> Icons.Filled.EventNote
        "book" -> Icons.Filled.Book
        "military_tech" -> Icons.Filled.MilitaryTech
        "chat" -> Icons.Filled.Chat
        "forum" -> Icons.Filled.Forum
        "palette" -> Icons.Filled.Palette
        "brush" -> Icons.Filled.Brush
        "psychology" -> Icons.Filled.Psychology
        "self_improvement" -> Icons.Filled.SelfImprovement
        "star" -> Icons.Filled.Star
        "workspace_premium" -> Icons.Filled.WorkspacePremium
        else -> Icons.Filled.EmojiEvents
    }
}

private fun getShopItemIcon(iconName: String): ImageVector {
    return when (iconName) {
        "wb_sunny" -> Icons.Filled.WbSunny
        "nightlight" -> Icons.Filled.Nightlight
        "local_florist" -> Icons.Filled.LocalFlorist
        "sentiment_very_satisfied" -> Icons.Filled.SentimentVerySatisfied
        "favorite" -> Icons.Filled.Favorite
        "auto_awesome" -> Icons.Filled.AutoAwesome
        "crop_square" -> Icons.Filled.CropSquare
        "photo_camera" -> Icons.Filled.PhotoCamera
        "emoji_events" -> Icons.Filled.EmojiEvents
        "verified" -> Icons.Filled.Verified
        else -> Icons.Filled.ShoppingBag
    }
}

private fun getShopCategoryColors(category: ShopCategory): List<Color> {
    return when (category) {
        ShopCategory.AVATAR -> listOf(Color(0xFFFF9800), Color(0xFFFFEB3B))
        ShopCategory.THEME -> listOf(Color(0xFF9C27B0), Color(0xFFE040FB))
        ShopCategory.STICKER -> listOf(Color(0xFFE91E63), Color(0xFFFF4081))
        ShopCategory.FRAME -> listOf(Color(0xFF2196F3), Color(0xFF03A9F4))
        ShopCategory.BADGE -> listOf(Color(0xFF4CAF50), Color(0xFF8BC34A))
    }
}
