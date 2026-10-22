package com.example.mydiary.ui.live2d

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.mydiary.live2d.CompanionAppearance
import com.example.mydiary.live2d.rememberCompanionAppearance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.live2d.Live2DComposable
import com.example.mydiary.live2d.Live2DConfig
import com.example.mydiary.live2d.PetOverlayService
import com.example.mydiary.live2d.VoiceWakeSettings
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val context = LocalContext.current
    val appearance = rememberCompanionAppearance()
    val currentModelPath = appearance.path
    val currentModelName = appearance.displayName
    
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
                        text = "数字人 · $currentModelName",
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
                        Live2DConfig.getModelByPath(newPath)?.let {
                            CompanionAppearance.select(context, it.id)
                        }
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
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        if (maxWidth >= 280.dp) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = currentModelName,
                                    modifier = Modifier.padding(horizontal = 108.dp),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                FloatingCompanionSwitch(Modifier.align(Alignment.CenterEnd))
                            }
                        } else {
                            // Keep the title centered and the control readable on narrow screens.
                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(currentModelName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                FloatingCompanionSwitch(Modifier.align(Alignment.End))
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "你的日记助手",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Live2DConfig.availableModels.forEach { model ->
                            val selected = appearance.id == model.id
                            Surface(
                                modifier = Modifier.weight(1f).selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { CompanionAppearance.select(context, model.id) }
                                ),
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) PrimaryLight else SoftWhite,
                                border = androidx.compose.foundation.BorderStroke(1.dp,
                                    if (selected) PrimaryVariant else SurfaceVariant)
                            ) {
                                Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        AsyncImage(model = "file:///android_asset/${model.avatarPath}",
                                            contentDescription = model.styleName,
                                            modifier = Modifier.size(40.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop)
                                        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "已选中",
                                            tint = PrimaryDark, modifier = Modifier.size(16.dp).align(Alignment.BottomEnd))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(model.styleName, fontSize = 13.sp, color = PrimaryDark,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    VoiceWakeSwitch()
                    Spacer(Modifier.height(8.dp))
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
                            description = "切换形象"
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
private fun VoiceWakeSwitch() {
    val context = LocalContext.current
    LaunchedEffect(context.applicationContext) { VoiceWakeSettings.initialize(context) }
    val enabled by VoiceWakeSettings.enabled.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PetOverlayService.refreshVoiceWake()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("语音唤醒", modifier = Modifier.weight(1f), fontSize = 14.sp, color = PrimaryDark)
        Switch(checked = enabled, modifier = Modifier.semantics { contentDescription = "语音唤醒" },
            onCheckedChange = { value ->
                VoiceWakeSettings.setEnabled(context, value)
                if (value && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    context.getSharedPreferences("companion", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("wake_permission_asked", true).apply()
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, colors = SwitchDefaults.colors(checkedThumbColor = Surface,
                checkedTrackColor = MintGreen, checkedBorderColor = MintGreen,
                uncheckedThumbColor = TextTertiary, uncheckedTrackColor = SurfaceVariant,
                uncheckedBorderColor = SurfaceVariant))
    }
}

@Composable
private fun FloatingCompanionSwitch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled by PetOverlayService.enabled.collectAsState()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("悬浮陪伴", fontSize = 12.sp, color = PrimaryDark)
        Switch(
            modifier = Modifier.semantics { contentDescription = "悬浮陪伴" },
            checked = enabled,
            onCheckedChange = { PetOverlayService.setEnabled(context, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Surface,
                checkedTrackColor = MintGreen,
                checkedBorderColor = MintGreen,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = SurfaceVariant,
                uncheckedBorderColor = SurfaceVariant
            )
        )
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
