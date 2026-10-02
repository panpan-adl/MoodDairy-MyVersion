package com.example.mydiary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.mydiary.navigation.Routes
import com.example.mydiary.ui.theme.MyDiaryTheme
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
    val navController = rememberNavController()
    var showQuickActions by remember { mutableStateOf(false) }

    val startDestination = remember {
        if (sessionManager.isLoggedIn()) Routes.CALENDAR else Routes.LOGIN
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

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
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
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
                            .offset(y = (-28).dp)
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
            floatingActionButtonPosition = FabPosition.Center
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavGraph(
                    navController = navController,
                    startDestination = startDestination,
                    sessionManager = sessionManager
                )
            }
        }

        if (showQuickActions) {
            QuickActionSheet(
                onDismiss = { showQuickActions = false },
                onAddTodo = {
                    showQuickActions = false
                    navController.navigate(Routes.todoCreate(getSelectedCalendarDate(navController)))
                },
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
    onAddTodo: () -> Unit,
    onCreateDiary: () -> Unit,
    onSearchDiary: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        ListItem(
            headlineContent = { Text("添加待办") },
            leadingContent = { Icon(Icons.Default.Checklist, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddTodo)
        )
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
    BottomAppBar(
        containerColor = Color.White.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        modifier = Modifier
            .height(64.dp)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.6f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ),
        actions = {
            BottomBarIcon(
                selected = currentRoute == Routes.CALENDAR,
                selectedIcon = Icons.Filled.DateRange,
                unselectedIcon = Icons.Outlined.DateRange,
                description = "日历",
                onClick = {
                    navController.navigate(Routes.CALENDAR) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )

            BottomBarIcon(
                selected = currentRoute == Routes.CHAT,
                selectedIcon = Icons.Filled.Chat,
                unselectedIcon = Icons.Outlined.Chat,
                description = "对话",
                onClick = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            BottomBarIcon(
                selected = currentRoute == Routes.EXPLORE,
                selectedIcon = Icons.Filled.Explore,
                unselectedIcon = Icons.Outlined.Explore,
                description = "发现",
                onClick = {
                    navController.navigate(Routes.EXPLORE) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )

            BottomBarIcon(
                selected = currentRoute == Routes.PROFILE,
                selectedIcon = Icons.Filled.Person,
                unselectedIcon = Icons.Outlined.Person,
                description = "我的",
                onClick = {
                    navController.navigate(Routes.PROFILE) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
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
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
            tint = if (selected) Color(0xFF9AEDCF) else Color(0xFF9E9E9E)
        )
    }
}
