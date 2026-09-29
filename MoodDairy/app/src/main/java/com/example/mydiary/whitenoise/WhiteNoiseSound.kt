package com.example.mydiary.whitenoise

import androidx.compose.ui.graphics.Color

/**
 * 白噪音声音数据类
 */
data class WhiteNoiseSound(
    val id: String,
    val name: String,
    val category: String,
    val resourceId: Int,
    val icon: String,
    val color: Color
)

/**
 * 白噪音分类
 */
enum class WhiteNoiseCategory(val displayName: String) {
    ANIMALS("动物声音"),
    NATURE("自然声音"),
    RAIN("雨声"),
    PLACES("场所声音"),
    THINGS("物品声音"),
    TRANSPORT("交通工具"),
    URBAN("城市声音"),
    NOISE("噪音")
}

