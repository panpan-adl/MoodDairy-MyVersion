package com.example.mydiary.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mydiary.data.models.SocialSearchItem
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.Surface as AppSurface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary

/**
 * 社交搜索结果横向滚动卡片列表，挂在 AI 消息气泡下方。
 */
@Composable
fun SocialSearchResultsRow(
    keyword: String?,
    items: List<SocialSearchItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (!keyword.isNullOrBlank()) {
            Text(
                text = "「$keyword」相关内容",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items) { item ->
                SocialSearchCard(item = item)
            }
        }
    }
}

@Composable
private fun SocialSearchCard(item: SocialSearchItem) {
    val context = LocalContext.current
    val platformLabel = when (item.platform) {
        "douyin" -> "抖音"
        "bilibili" -> "B站"
        "zhihu" -> "知乎"
        "kuaishou" -> "快手"
        "weibo" -> "微博"
        "netease" -> "网易云"
        else -> "小红书"
    }
    val platformColor = when (item.platform) {
        "douyin" -> Color(0xFF161823)
        "bilibili" -> Color(0xFFFB7299)
        "zhihu" -> Color(0xFF0066FF)
        "kuaishou" -> Color(0xFFFF5000)
        "weibo" -> Color(0xFFE6162D)
        "netease" -> Color(0xFFEC4141)
        else -> Color(0xFFFF2442)
    }

    Card(
        modifier = Modifier
            .width(180.dp)
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                    context.startActivity(intent)
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0xFFEAEAEA)),
            ) {
                if (!item.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 平台角标
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(platformColor.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = platformLabel,
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // 视频标识
                if (item.contentType == "video") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "video",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                // 音乐标识
                if (item.contentType == "music") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "music",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = item.authorName ?: "",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 90.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "likes",
                            tint = Color(0xFFFF6B81),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = formatLikeCount(item.likeCount),
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

private fun formatLikeCount(count: Int): String {
    return when {
        count >= 10000 -> String.format("%.1fw", count / 10000.0)
        count >= 1000 -> String.format("%.1fk", count / 1000.0)
        else -> count.toString()
    }
}

/**
 * 会员解锁卡片：非会员触发搜索时显示，引导去充值。
 */
@Composable
fun VipUnlockCard(
    onRecharge: () -> Unit,
    modifier: Modifier = Modifier,
    errorCode: String? = null,
) {
    val isNoKey = errorCode == "NO_KEY" || errorCode == null
    val title = if (isNoKey) "绑定 TikHub 密钥，解锁全网搜索" else "TikHub 余额不足，去充值后继续搜索"
    val subtitle = if (isNoKey) {
        "免费注册，B站、知乎免费搜；小红书 / 抖音 / 快手 / 微博约 $0.01/次"
    } else {
        "搜索约 $0.01/次，支持支付宝 / 微信付款"
    }
    val actionText = if (isNoKey) "去绑定" else "去充值"
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onRecharge),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F3)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧皇冠/星标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF6B81)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "vip",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFF6B81))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = actionText,
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

