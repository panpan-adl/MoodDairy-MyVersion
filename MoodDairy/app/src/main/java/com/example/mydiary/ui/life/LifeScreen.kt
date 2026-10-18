package com.example.mydiary.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    // Start review reads while the Today tab is visible.
    val reviewViewModel: com.example.mydiary.ui.explore.ExploreViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    LifeContent(requestedSection, onSectionRequestHandled,
        today = { TodayScreen(onOpenAssistant, onOpenRecords, onOpenDiary, showTitle = false) },
        review = { ExploreScreen(onOpenGrowthPortrait, onOpenHighlights, onOpenLittleJoys,
            onOpenDiary, viewModel = reviewViewModel, showTitle = false) })
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
    Column(Modifier.fillMaxSize().background(LifeStyle.background)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = LifeStyle.field,
            border = androidx.compose.foundation.BorderStroke(1.dp, LifeStyle.border),
        ) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("today" to "当下", "review" to "足迹").forEach { (key, label) ->
                    val selected = section == key
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) LifeStyle.selected else Color.Transparent,
                    ) {
                        Tab(
                            selected = selected,
                            onClick = { section = key },
                            modifier = Modifier.heightIn(min = 48.dp),
                            selectedContentColor = LifeStyle.ink,
                            unselectedContentColor = LifeStyle.mutedInk,
                        ) {
                            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            stateHolder.SaveableStateProvider(section) {
                if (section == "today") today() else review()
            }
        }
    }
}
