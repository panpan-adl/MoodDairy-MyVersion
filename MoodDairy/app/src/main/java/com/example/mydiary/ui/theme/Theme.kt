package com.example.mydiary.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 应用排版样式
 * 保持原有配置，适合阅读
 */
private val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * 深色主题配色
 * 即使在深色模式下，也尽量保留薄荷绿的特征，但降低亮度以护眼
 */
private val DarkColorScheme = darkColorScheme(
    primary = Primary,           // 保持薄荷绿作为强调
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryDark,
    tertiary = SecondaryVariant,
    background = Color(0xFF121212), // 深灰背景
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),    // 深灰卡片
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Error,
    onError = OnError,
    outline = Color(0xFF5A5A5A)
)

/**
 * 浅色主题配色 (治愈系核心风格)
 * 对应设计图的薄荷绿(#9AEDCF)和淡黄绿(#EBFFC7)
 */
private val LightColorScheme = lightColorScheme(
    primary = Primary,              // 薄荷绿 (MintGreen)
    onPrimary = OnPrimary,          // 深色文字 (因为背景是浅绿)
    primaryContainer = PrimaryLight,// 更浅的薄荷绿
    onPrimaryContainer = OnPrimaryDark,
    
    secondary = Secondary,          // 淡黄绿 (PaleYellow)
    onSecondary = OnSecondary,      // 深色文字
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = OnSecondary,
    
    tertiary = SecondaryVariant,
    
    background = Background,        // 柔和白 (SoftWhite)
    onBackground = OnBackground,
    
    surface = Surface,              // 纯白卡片
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    
    error = Error,
    onError = OnError,
    outline = DividerColor,
    outlineVariant = Color(0xFFF0F0F0)
)

/**
 * 心语日记主题
 * 
 * @param darkTheme 是否使用深色主题
 * @param dynamicColor 是否使用动态颜色（注意：默认为 false 以保证治愈系风格一致性）
 * @param content 内容组件
 */
@Composable
fun MyDiaryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 关键修改：默认设为 false。如果为 true，Android 12+ 会强制使用壁纸取色，破坏设计图的薄荷绿风格
    dynamicColor: Boolean = false, 
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            
            // 设置状态栏颜色为主色 (薄荷绿)
            // 如果希望状态栏是白色的（融合背景），这里可以改为 colorScheme.background
            window.statusBarColor = colorScheme.primary.toArgb()
            
            // 状态栏图标颜色控制：
            // 因为我们的 Primary (薄荷绿) 是浅色，所以 Light Mode 下状态栏图标必须是深色 (true)
            // Dark Mode 下状态栏图标是浅色 (false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}