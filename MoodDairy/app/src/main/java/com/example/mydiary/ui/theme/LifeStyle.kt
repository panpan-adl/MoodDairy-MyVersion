package com.example.mydiary.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Shared surfaces for the present and the memories collected in Life. */
internal object LifeStyle {
    val background get() = Brush.verticalGradient(listOf(PageTopBackground, PageMiddleBackground, Color(0xFFFFFCF5)))
    val overview get() = Brush.linearGradient(listOf(Color(0xFFD1EEDF), Color(0xFFE5F2D9)))
    val border = Color(0xFFB9DFCB)
    val paper = Color(0xFFFAF8EF)
    val paperInset = Color(0xFFF0EFE5)
    val paperBorder = Color(0xFFE7E8D9)
    val mint = Color(0xFFE0F1E8)
    val leaf = Color(0xFFEAF4E9)
    val cream = Color(0xFFFFF5DD)
    val creamBorder = Color(0xFFE6D8B8)
    val field = Color.White.copy(alpha = 0.55f)
    val selected = Color(0xFFBFE8D5)
    val ink = Color(0xFF276F57)
    val mutedInk = Color(0xFF5C756B)
    val disabled = Color(0xFFDCE7DF)
    val disabledInk = Color(0xFF687D72)
}
