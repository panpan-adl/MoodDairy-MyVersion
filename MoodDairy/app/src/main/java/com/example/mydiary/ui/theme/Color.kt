package com.example.mydiary.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. 核心品牌色 (基于治愈系设计图)
// ==========================================
val MintGreen = Color(0xFF9AEDCF)      // 设计图核心主色
val PaleYellow = Color(0xFFEBFFC7)     // 设计图辅助色
val SoftWhite = Color(0xFFF9FDFB)      // 全局背景色 (带一点点绿的白，更护眼)

// ==========================================
// 2. Material Theme 映射 (在原有变量上修改值)
// ==========================================

// 主色调 - 现在使用浅薄荷绿作为主视觉
val Primary = MintGreen
val PrimaryVariant = Color(0xFF5CB89D) // 保留你原来的深色作为变体 (用于Status Bar或深色模式)
val PrimaryDark = Color(0xFF3A7D6B)
val PrimaryLight = Color(0xFFC7F5E3)   // 比主色更浅的色调

// 辅助色 - 淡黄绿
val Secondary = PaleYellow
val SecondaryVariant = Color(0xFFBDD199) // 保留原来的深色作为变体
val SecondaryDark = Color(0xFF8FA371)
val SecondaryLight = Color(0xFFF5FFE0)

// 背景色 - 使用极其柔和的色调
val Background = SoftWhite
val Surface = Color(0xFFFFFFFF)        // 卡片背景保持纯白，产生微弱层次感
val SurfaceVariant = Color(0xFFF0F5F3) // 略带绿灰的表面变体

// ==========================================
// 3. 文字颜色 (通用定义，保留你原有的结构)
// ==========================================
val TextPrimary = Color(0xFF2C3E50)    // 稍微带点蓝的深灰，比纯黑(#1A1A1A)更柔和，符合插画风
val TextSecondary = Color(0xFF7F8C8D)  // 次要文字
val TextTertiary = Color(0xFF95A5A6)   // 辅助文字
val TextDisabled = Color(0xFFBDC3C7)   // 禁用文字

// ==========================================
// 4. 在彩色背景上的文字颜色 (关键修改！)
// ==========================================
// 因为 Primary (薄荷绿) 是浅色，所以上面的文字必须是深色！
val OnPrimary = TextPrimary     // <--- 修改：浅色按钮上的文字要是深色
val OnPrimaryDark = Color.White // 深色变体上可以用白色文字
val OnSecondary = TextPrimary   // <--- 修改：浅黄色上的文字要是深色
val OnBackground = TextPrimary
val OnSurface = TextPrimary

// ==========================================
// 5. 功能性颜色 (保留原有结构，微调色值)
// ==========================================
val Error = Color(0xFFE57373)   // 调柔和一点的红色，不要太刺眼
val OnError = Color.White

val IconTint = TextPrimary      // 图标颜色跟随主要文字
val IconTintLight = TextSecondary
val DividerColor = Color(0xFFEEEEEE) // 更淡的分割线

// 状态颜色 (调成糖果色系以匹配风格)
val SuccessColor = Color(0xFF81C784)
val WarningColor = Color(0xFFFFB74D)
val InfoColor = Color(0xFF64B5F6)

// ==========================================
// 6. 新增：情感分析专用色 (对应设计图)
// ==========================================
val EmotionLow = Color(0xFFAED9E0)     // 忧郁/低落 (淡蓝)
val EmotionHappy = Color(0xFFFFE4B5)   // 开心 (暖黄)
val EmotionCalm = MintGreen            // 平静 (跟随主色)
val EmotionAnger = Color(0xFFFFCDD2)   // 愤怒 (淡红)