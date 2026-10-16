package com.example.mydiary
import androidx.compose.material3.TextButton
import com.example.mydiary.ui.theme.TextPrimary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.music.MusicFloatingLayer
import com.example.mydiary.music.MusicWidgetController
import com.example.mydiary.navigation.NavGraph
import com.example.mydiary.navigation.navigateMainTab
import com.example.mydiary.navigation.Routes
import com.example.mydiary.ui.theme.MyDiaryTheme
import com.example.mydiary.ui.layout.KeyboardAwareScaffold
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var musicWidgetController: MusicWidgetController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyDiaryTheme {
                MainScreen(
                    sessionManager = sessionManager,
                    musicWidgetController = musicWidgetController,
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    sessionManager: SessionManager,
    musicWidgetController: MusicWidgetController,
) {
    val memoryContext = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    var showQuickActions by remember { mutableStateOf(false) }

    val startDestination = remember {
        if (sessionManager.isLoggedIn()) Routes.CHAT else Routes.LOGIN
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    com.example.mydiary.live2d.CompanionHost(
        active = currentRoute != null && currentRoute != Routes.LOGIN && currentRoute != Routes.REGISTER && currentRoute != Routes.LIVE2D,
        assistant = currentRoute == Routes.CHAT,
    )
    androidx.compose.runtime.LaunchedEffect(navController) {
        com.example.mydiary.live2d.PetOverlayService.clientActions.collect { action ->
            if (sessionManager.isLoggedIn()) {
                com.example.mydiary.navigation.handleClientAction(navController, memoryContext, action)
            }
        }
    }



    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.LOGIN) {
            com.example.mydiary.live2d.PetOverlayService.clearVoiceActivation()
            com.example.mydiary.whitenoise.WhiteNoiseManager.getInstance().stopAllSounds()
        }
    }

    val showBottomBar = when {
        currentRoute == Routes.LOGIN -> false
        currentRoute == Routes.REGISTER -> false
        currentRoute?.contains("editor", ignoreCase = true) == true -> false
        else -> true
    }

    val showMainFAB = when {
        currentRoute == Routes.CALENDAR -> true
        else -> false
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF9AEDCF),
            Color(0xFFEBFFC7)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush)
    ) {
        KeyboardAwareScaffold(
            hasTopBar = false,
            topBar = {},
            hideNavigationForKeyboard = currentRoute == Routes.CHAT,
            bottomBar = {
                if (showBottomBar) {
                    BottomNavBar(navController = navController, currentRoute = currentRoute)
                }
            },
            floatingActionButton = {
                if (showMainFAB) {
                    FloatingActionButton(
                        onClick = { showQuickActions = true },
                        containerColor = Color(0xFF9AEDCF),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .offset(y = 0.dp)
                            .size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Quick actions",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            },
        ) {
                NavGraph(
                    navController = navController,
                    startDestination = startDestination,
                    sessionManager = sessionManager
                )
        }

        if (showQuickActions) {
            QuickActionSheet(
                onDismiss = { showQuickActions = false },
                onCreateDiary = {
                    showQuickActions = false
                    navController.navigate(Routes.diaryEditor(getSelectedCalendarDate(navController)))
                },
                onSearchDiary = {
                    showQuickActions = false
                    navController.navigate(Routes.DIARY_SEARCH)
                }
            )
        }

        // 「听点音乐」悬浮层：推荐弹窗 + 可拖拽迷你播放条，跨页面存活
        MusicFloatingLayer(controller = musicWidgetController)
    }
}

private fun getSelectedCalendarDate(navController: NavController): LocalDate {
    val raw = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.get<String>("calendar_selected_date")
    return raw?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    } ?: LocalDate.now()
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun QuickActionSheet(
    onDismiss: () -> Unit,
    onCreateDiary: () -> Unit,
    onSearchDiary: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        ListItem(
            headlineContent = { Text("创建日记") },
            leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCreateDiary)
        )
        ListItem(
            headlineContent = { Text("输入关键字检索日记") },
            leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSearchDiary)
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun BottomNavBar(navController: NavController, currentRoute: String?) {
    val tabs = listOf(
        Triple(Routes.CALENDAR, "记录", Icons.Filled.DateRange to Icons.Outlined.DateRange),
        Triple(Routes.CHAT, "助手", Icons.Filled.Chat to Icons.Outlined.Chat),
        Triple(Routes.LIFE, "生活", Icons.Filled.Explore to Icons.Outlined.Explore),
        Triple(Routes.PROFILE, "我的", Icons.Filled.Person to Icons.Outlined.Person)
    )
    BottomAppBar(
        containerColor = Color.White.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        // Keep the bar's content height plus its system navigation inset.
        // A fixed total height clips labels on devices with gesture navigation.
        actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEach { (route, label, icons) ->
                    BottomBarIcon(
                        selected = currentRoute == route,
                        selectedIcon = icons.first,
                        unselectedIcon = icons.second,
                        description = label,
                        onClick = {
                            navController.navigateMainTab(route)
                        }
                    )
                }
            }
        }
    )
}
@Composable
private fun BottomBarIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description,
            modifier = Modifier.size(24.dp),
            tint = if (selected) Color(0xFF44866A) else Color(0xFF9E9E9E)
        )
        Text(description, style = MaterialTheme.typography.labelSmall, color = if (selected) Color(0xFF44866A) else Color(0xFF777777))
        }
    }
}
