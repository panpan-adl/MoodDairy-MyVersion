package com.example.mydiary.ui.test

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.TestEntryType
import com.example.mydiary.data.models.TestHistoryItem
import com.example.mydiary.data.models.TestMeta
import com.example.mydiary.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 心理测试中心页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTest: (String) -> Unit,
    viewModel: TestViewModel = hiltViewModel()
) {
    val hubState by viewModel.hubState.collectAsState()
    val history by viewModel.history.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadTestIndex()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "测试中心",
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
                .padding(paddingValues)
        ) {
            if (hubState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Primary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 热门推荐
                    item {
                        Text(
                            text = "发现有趣的心理学测试",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 快捷入口
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TestEntryCard(
                                entryType = TestEntryType.MBTI,
                                icon = Icons.Outlined.GridView,
                                backgroundColor = MintGreen.copy(alpha = 0.15f),
                                onClick = { onNavigateToTest("mbti_simplified_v1") },
                                modifier = Modifier.weight(1f)
                            )
                            TestEntryCard(
                                entryType = TestEntryType.ANIMAL,
                                icon = Icons.Outlined.Pets,
                                backgroundColor = PaleYellow.copy(alpha = 0.3f),
                                onClick = { onNavigateToTest("animal_personality_v1") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TestEntryCard(
                                entryType = TestEntryType.EMOTION,
                                icon = Icons.Outlined.Mood,
                                backgroundColor = Color(0xFFE8F5E9),
                                onClick = { onNavigateToTest("phq9_v1") },
                                modifier = Modifier.weight(1f)
                            )
                            TestEntryCard(
                                entryType = TestEntryType.POTENTIAL,
                                icon = Icons.Outlined.Lightbulb,
                                backgroundColor = Color(0xFFFFF3E0),
                                onClick = { onNavigateToTest("holland_simplified_v1") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // 全部测试
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "全部测试",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    if (history.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "历史记录",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        items(history.take(10)) { historyItem ->
                            TestHistoryListItem(
                                item = historyItem,
                                onClick = { onNavigateToTest(historyItem.testId) }
                            )
                        }
                    }

                    hubState.testIndex?.tests?.let { tests ->
                        items(tests) { test ->
                            TestListItem(
                                test = test,
                                onClick = { onNavigateToTest(test.testId) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }

            // 错误提示
            hubState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(error)
                }
            }
        }
    }
}

/**
 * 快捷测试入口卡片
 */
@Composable
private fun TestEntryCard(
    entryType: TestEntryType,
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = entryType.title,
                tint = Primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = entryType.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entryType.subtitle,
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 测试列表项
 */
@Composable
private fun TestListItem(
    test: TestMeta,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MintGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getIconForTest(test.icon),
                    contentDescription = test.name,
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = test.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )

                    test.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${test.questionCount} 道题 · 约 ${test.timeMinutes} 分钟",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "开始测试",
                tint = TextTertiary
            )
        }
    }
}

/**
 * 根据icon名称返回对应的图标
 */
private fun getIconForTest(iconName: String): ImageVector {
    return when (iconName) {
        "person" -> Icons.Outlined.Person
        "pets" -> Icons.Outlined.Pets
        "mood" -> Icons.Outlined.Mood
        "psychology" -> Icons.Outlined.Psychology
        "today" -> Icons.Outlined.Today
        "work" -> Icons.Outlined.Work
        "favorite" -> Icons.Outlined.Favorite
        else -> Icons.Outlined.Quiz
    }
}


@Composable
private fun TestHistoryListItem(
    item: TestHistoryItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = item.testName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.resultSummary,
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp)),
                fontSize = 12.sp,
                color = TextTertiary
            )
        }
    }
}
