package com.example.mydiary.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.agent.TodayScreen
import com.example.mydiary.ui.explore.ExploreScreen
import com.example.mydiary.ui.theme.*

@Composable
fun LifeScreen(
    requestedSection: String?,
    onSectionRequestHandled: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenDiary: (Long) -> Unit,
    onOpenGrowthPortrait: () -> Unit,
    onOpenHighlights: () -> Unit,
    onOpenLittleJoys: () -> Unit,
) {
    LifeContent(requestedSection, onSectionRequestHandled,
        today = { TodayScreen(onOpenAssistant, onOpenRecords, onOpenDiary, showTitle = false) },
        review = { ExploreScreen(onOpenGrowthPortrait, onOpenHighlights, onOpenLittleJoys,
            onOpenDiary, showTitle = false) })
}

@Composable
internal fun LifeContent(
    requestedSection: String? = null,
    onSectionRequestHandled: () -> Unit = {},
    today: @Composable () -> Unit,
    review: @Composable () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf("today") }
    val stateHolder = rememberSaveableStateHolder()
    LaunchedEffect(requestedSection) {
        if (requestedSection != null) {
            section = if (requestedSection == "review") "review" else "today"
            onSectionRequestHandled()
        }
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(MintGreen.copy(alpha = 0.3f), PaleYellow.copy(alpha = 0.3f), SoftWhite)))) {
        TabRow(selectedTabIndex = if (section == "today") 0 else 1,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = PrimaryDark) {
            Tab(selected = section == "today", onClick = { section = "today" }, text = { Text("今天") })
            Tab(selected = section == "review", onClick = { section = "review" }, text = { Text("回顾") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            stateHolder.SaveableStateProvider(section) {
                if (section == "today") today() else review()
            }
        }
    }
}
