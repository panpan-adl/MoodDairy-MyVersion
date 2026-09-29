package com.example.mydiary.ui.timer

import android.os.CountDownTimer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.*
import kotlinx.coroutines.delay

enum class TimerState {
    IDLE,       // 未开始
    RUNNING,    // 运行中
    PAUSED,     // 暂停
    COMPLETED   // 完成
}

enum class TimerMode(val workMinutes: Int, val breakMinutes: Int, val label: String) {
    POMODORO(25, 5, "番茄钟"),
    SHORT_BREAK(15, 3, "短休息"),
    LONG_BREAK(30, 7, "长休息")
}

@Composable
fun PomodoroTimerScreen(
    onNavigateBack: () -> Unit,
    onSessionComplete: (Int) -> Unit = {}
) {
    var timerMode by remember { mutableStateOf(TimerMode.POMODORO) }
    var timerState by remember { mutableStateOf(TimerState.IDLE) }
    var remainingSeconds by remember { mutableIntStateOf(timerMode.workMinutes * 60) }
    var completedSessions by remember { mutableIntStateOf(0) }

    var countDownTimer by remember { mutableStateOf<CountDownTimer?>(null) }

    // 计时器逻辑
    fun startTimer() {
        if (timerState == TimerState.RUNNING) return

        countDownTimer = object : CountDownTimer(remainingSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                timerState = TimerState.COMPLETED
                if (timerMode == TimerMode.POMODORO) {
                    completedSessions++
                    onSessionComplete(completedSessions)
                }
            }
        }.start()

        timerState = TimerState.RUNNING
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        timerState = TimerState.PAUSED
    }

    fun resetTimer() {
        countDownTimer?.cancel()
        remainingSeconds = timerMode.workMinutes * 60
        timerState = TimerState.IDLE
    }

    fun switchMode(mode: TimerMode) {
        countDownTimer?.cancel()
        timerMode = mode
        remainingSeconds = if (timerState == TimerState.IDLE || timerState == TimerState.COMPLETED) {
            if (mode == TimerMode.POMODORO) mode.workMinutes * 60 else mode.breakMinutes * 60
        } else {
            mode.workMinutes * 60
        }
        timerState = TimerState.IDLE
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            countDownTimer?.cancel()
        }
    }

    // 动画
    val scale by animateFloatAsState(
        targetValue = if (timerState == TimerState.RUNNING) 1.02f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val progress = remainingSeconds.toFloat() / (timerMode.workMinutes * 60).toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (timerMode == TimerMode.POMODORO)
                            Color(0xFFFFE0E0).copy(alpha = 0.3f)
                        else
                            MintGreen.copy(alpha = 0.3f),
                        PaleYellow.copy(alpha = 0.3f),
                        SoftWhite,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }

                Text(
                    text = "专注计时器",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // 完成次数
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            Color(0xFFFFF8E1),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.LocalFlorist,
                        contentDescription = null,
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$completedSessions",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF8F00)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 模式选择
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimerMode.entries.forEach { mode ->
                    val isSelected = timerMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { switchMode(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (mode == TimerMode.POMODORO)
                                Color(0xFFFF6B6B) else MintGreen,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // 番茄图标和计时器
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .scale(scale),
                contentAlignment = Alignment.Center
            ) {
                // 进度背景圆环
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = if (timerMode == TimerMode.POMODORO)
                        Color(0xFFFF6B6B) else MintGreen,
                    strokeWidth = 12.dp,
                    trackColor = Color.Gray.copy(alpha = 0.1f),
                )

                // 番茄图标
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 使用食物/植物图标代表番茄
                    Icon(
                        imageVector = Icons.Filled.Spa,
                        contentDescription = "番茄",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(64.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 计时器文字
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 状态文字
                    Text(
                        text = when (timerState) {
                            TimerState.IDLE -> "点击开始"
                            TimerState.RUNNING -> "专注中..."
                            TimerState.PAUSED -> "已暂停"
                            TimerState.COMPLETED -> "完成!"
                        },
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // 控制按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 重置按钮
                if (timerState != TimerState.IDLE) {
                    IconButton(
                        onClick = { resetTimer() },
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                Color.Gray.copy(alpha = 0.1f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重置",
                            tint = TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // 开始/暂停按钮
                Button(
                    onClick = {
                        when (timerState) {
                            TimerState.IDLE, TimerState.COMPLETED -> startTimer()
                            TimerState.RUNNING -> pauseTimer()
                            TimerState.PAUSED -> startTimer()
                        }
                    },
                    modifier = Modifier
                        .size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timerMode == TimerMode.POMODORO)
                            Color(0xFFFF6B6B) else MintGreen
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 4.dp
                    )
                ) {
                    Icon(
                        imageVector = when (timerState) {
                            TimerState.RUNNING -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (timerState == TimerState.RUNNING) "暂停" else "开始",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // 跳过按钮（运行时）
                if (timerState == TimerState.RUNNING || timerState == TimerState.PAUSED) {
                    IconButton(
                        onClick = {
                            countDownTimer?.cancel()
                            // 切换到下一个模式
                            val nextMode = when (timerMode) {
                                TimerMode.POMODORO -> TimerMode.SHORT_BREAK
                                TimerMode.SHORT_BREAK -> TimerMode.POMODORO
                                TimerMode.LONG_BREAK -> TimerMode.POMODORO
                            }
                            switchMode(nextMode)
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                Color.Gray.copy(alpha = 0.1f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "跳过",
                            tint = TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 提示文字
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (timerMode == TimerMode.POMODORO)
                        Color(0xFFFFF8E1) else Color(0xFFE8F5E9)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = if (timerMode == TimerMode.POMODORO)
                            Color(0xFFFF8F00) else Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (timerMode == TimerMode.POMODORO)
                            "番茄工作法：专注25分钟，休息5分钟，完成4个番茄后可长休息"
                        else
                            "适当休息可以帮助恢复精力，提高效率",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
