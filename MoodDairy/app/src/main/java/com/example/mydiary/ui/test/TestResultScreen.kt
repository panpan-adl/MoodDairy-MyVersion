package com.example.mydiary.ui.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.TestResult
import com.example.mydiary.ui.theme.*

/**
 * 测试结果页面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TestResultScreen(
    testId: String,
    onNavigateBack: () -> Unit,
    onRetakeTest: () -> Unit,
    viewModel: TestViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(testId) {
        viewModel.loadTest(testId, preserveResult = true)
    }

    LaunchedEffect(uiState.isLoading, uiState.result) {
        if (!uiState.isLoading && uiState.result == null) {
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测试结果", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
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
            when (val result = uiState.result) {
                is TestResult.MbtiResult -> MbtiResultContent(result, onRetakeTest)
                is TestResult.AnimalResult -> AnimalResultContent(result, onRetakeTest)
                is TestResult.ScoreResult -> ScoreResultContent(result, onRetakeTest)
                is TestResult.HollandResult -> HollandResultContent(result, onRetakeTest)
                null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无结果", color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onRetakeTest) {
                            Text("重新测试")
                        }
                    }
                }
            }
        }
    }
}

/**
 * MBTI结果内容
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MbtiResultContent(
    result: TestResult.MbtiResult,
    onRetakeTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 类型卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = result.emoji,
                        fontSize = 64.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = result.typeCode,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    
                    Text(
                        text = result.typeName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = result.brief,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // 描述
        item {
            ResultSection(title = "性格描述") {
                Text(
                    text = result.description,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }
        }
        
        // 优势
        item {
            ResultSection(title = "你的优势") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    result.strengths.forEach { strength ->
                        TraitChip(text = strength, color = Primary)
                    }
                }
            }
        }
        
        // 成长建议
        item {
            ResultSection(title = "成长建议") {
                result.growthAreas.forEach { area ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TipsAndUpdates,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = area,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
        
        // 著名人物
        if (result.famousPeople.isNotEmpty()) {
            item {
                ResultSection(title = "同类型名人") {
                    Text(
                        text = result.famousPeople.joinToString("、"),
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }
        }
        
        // 重测按钮
        item {
            RetakeButton(onRetakeTest)
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 动物人格结果内容
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimalResultContent(
    result: TestResult.AnimalResult,
    onRetakeTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 动物卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PaleYellow.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = result.emoji,
                        fontSize = 80.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = result.animal,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    
                    Text(
                        text = result.animalEn,
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = result.brief,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // 宠物解锁提示
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MintGreen.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Celebration,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "恭喜！你已解锁专属虚拟宠物伙伴",
                        fontSize = 14.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        // 描述
        item {
            ResultSection(title = "性格描述") {
                Text(
                    text = result.description,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }
        }
        
        // 特质
        item {
            ResultSection(title = "你的特质") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    result.traits.forEach { trait ->
                        TraitChip(text = trait, color = Color(0xFFFF9800))
                    }
                }
            }
        }
        
        // 重测按钮
        item {
            RetakeButton(onRetakeTest)
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 分数型结果内容（PHQ-9, GAD-7等）
 */
@Composable
private fun ScoreResultContent(
    result: TestResult.ScoreResult,
    onRetakeTest: () -> Unit
) {
    val levelColor = try {
        Color(android.graphics.Color.parseColor(result.color))
    } catch (e: Exception) {
        Primary
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 分数卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 分数圆环
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(levelColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${result.totalScore}",
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = levelColor
                            )
                            Text(
                                text = "/ ${result.maxScore}",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 等级标签
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = levelColor
                    ) {
                        Text(
                            text = result.levelName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        
        // 危险提示
        if (result.hasCriticalFlag && result.criticalMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = result.criticalMessage!!,
                            fontSize = 14.sp,
                            color = Color(0xFFC62828),
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
        
        // 描述
        item {
            ResultSection(title = "结果解读") {
                Text(
                    text = result.description,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }
        }
        
        // 建议
        item {
            ResultSection(title = "建议") {
                Text(
                    text = result.suggestion,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }
        }
        
        // 免责声明
        item {
            Text(
                text = "本测试仅供参考，不能替代专业的临床诊断。如您持续感到困扰，建议寻求专业心理咨询师或医生的帮助。",
                fontSize = 12.sp,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // 重测按钮
        item {
            RetakeButton(onRetakeTest)
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 霍兰德结果内容
 */
@Composable
private fun HollandResultContent(
    result: TestResult.HollandResult,
    onRetakeTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 类型卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Work,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(64.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "你的职业类型代码",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    
                    Text(
                        text = result.typeCode,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
            }
        }
        
        // 维度得分
        item {
            ResultSection(title = "各维度得分") {
                val dimensionNames = mapOf(
                    "R" to "现实型",
                    "I" to "研究型",
                    "A" to "艺术型",
                    "S" to "社会型",
                    "E" to "企业型",
                    "C" to "常规型"
                )
                
                result.dimensionScores.forEach { (code, score) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$code - ${dimensionNames[code] ?: code}",
                            fontSize = 14.sp,
                            color = TextPrimary,
                            modifier = Modifier.width(100.dp)
                        )
                        
                        LinearProgressIndicator(
                            progress = { (score.toFloat() / 14f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Primary,
                            trackColor = Primary.copy(alpha = 0.2f)
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = score.toString(),
                            fontSize = 14.sp,
                            color = TextSecondary,
                            modifier = Modifier.width(30.dp)
                        )
                    }
                }
            }
        }
        
        // 描述
        if (result.description.isNotEmpty()) {
            item {
                ResultSection(title = "结果描述") {
                    Text(
                        text = result.description,
                        fontSize = 15.sp,
                        color = TextPrimary,
                        lineHeight = 24.sp
                    )
                }
            }
        }
        
        // 重测按钮
        item {
            RetakeButton(onRetakeTest)
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 结果区块
 */
@Composable
private fun ResultSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            content()
        }
    }
}

/**
 * 特质标签
 */
@Composable
private fun TraitChip(
    text: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 重测按钮
 */
@Composable
private fun RetakeButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Primary
        ),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "重新测试",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
