package com.example.mydiary.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.example.mydiary.ui.components.PageScrollbarHost

/** One owner for system/IME insets; children receive already-consumed padding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardAwareScaffold(
    hasTopBar: Boolean,
    hideNavigationForKeyboard: Boolean,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            if (hasTopBar) Box(Modifier.statusBarsPadding().testTag("app_fixed_topbar")) { topBar() }
        },
        bottomBar = {
            if (!hideNavigationForKeyboard || !imeVisible) {
                Box(Modifier.testTag("app_bottom_navigation")) { bottomBar() }
            }
        },
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { PageScrollbarHost { content() } }
    }
}
