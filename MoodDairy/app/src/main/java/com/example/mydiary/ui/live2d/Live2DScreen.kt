package com.example.mydiary.ui.live2d

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.live2d.Live2DComposable
import com.example.mydiary.live2d.Live2DConfig
import com.example.mydiary.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Live2D 角色展示界面
 * 展示小嘉然的 Live2D 动画，支持交互
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Live2DScreen(
    onNavigateBack: () -> Unit = {}
) {
    var currentModelPath by remember { mutableStateOf(Live2DConfig.defaultModel.path) }
    var currentModelName by remember { mutableStateOf(Live2DConfig.defaultModel.displayName) }
    
    // 退出动画状态
    var isExiting by remember { mutableStateOf(false) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "exitAlpha"
    )
    
    // 监听退出动画完成
    LaunchedEffect(isExiting) {
        if (isExiting) {
            delay(280) // 等待动画完成
            onNavigateBack()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(exitAlpha)
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
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = {
                    Text(
                        text = "小嘉然",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!isExiting) isExiting = true },
                        enabled = !isExiting
                    ) {
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
            
            // Live2D 展示区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                // Live2D 角色（通过 alpha 参数控制淡出）
                Live2DComposable(
                    modelPath = currentModelPath,
                    modifier = Modifier.fillMaxSize(),
                    alpha = exitAlpha,
                    onModelChanged = { newPath ->
                        currentModelPath = newPath
                        currentModelName = Live2DConfig.availableModels
                            .find { it.path == newPath }?.displayName ?: "小嘉然"
                    }
                )
            }
            
            // 底部信息卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
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
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentModelName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "你的日记助手",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 交互提示
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InteractionTip(
                            title = "单击",
                            description = "播放动作"
                        )
                        InteractionTip(
                            title = "双击",
                            description = "切换角色"
                        )
                        InteractionTip(
                            title = "触摸",
                            description = "互动反应"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionTip(
    title: String,
    description: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(MintGreen, PaleYellow)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = description,
            fontSize = 12.sp,
            color = TextTertiary
        )
    }
}
