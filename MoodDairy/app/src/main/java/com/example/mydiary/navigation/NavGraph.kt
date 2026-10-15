package com.example.mydiary.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.ui.agent.TodayScreen
import com.example.mydiary.ui.auth.LoginScreen
import com.example.mydiary.ui.auth.RegisterScreen
import com.example.mydiary.ui.calendar.CalendarScreen
import com.example.mydiary.ui.calendar.CalendarViewModel
import com.example.mydiary.ui.chat.ChatScreen
import com.example.mydiary.ui.drawing.DrawingScreen
import com.example.mydiary.ui.drawing.DrawingResultScreen
import com.example.mydiary.ui.drawing.SharedDrawingViewModel
import com.example.mydiary.ui.editor.DiaryEditorScreen
import com.example.mydiary.ui.explore.ExploreScreen
import com.example.mydiary.ui.explore.GrowthPortraitScreen
import com.example.mydiary.ui.explore.HighlightsScreen
import com.example.mydiary.ui.explore.LittleJoysScreen
import com.example.mydiary.ui.profile.EditProfileScreen
import com.example.mydiary.ui.profile.ProfileScreen
import com.example.mydiary.ui.search.DiarySearchScreen
import com.example.mydiary.ui.test.TestHubScreen
import com.example.mydiary.ui.test.TestScreen
import com.example.mydiary.ui.test.TestResultScreen
import com.example.mydiary.ui.vip.RechargeScreen
import com.example.mydiary.ui.relax.RelaxScreen
import com.example.mydiary.whitenoise.WhiteNoiseManager
import com.example.mydiary.whitenoise.WhiteNoiseSoundList
import com.example.mydiary.ui.live2d.Live2DScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

private const val TAG = "DiaryNavDebug"

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val TODAY = "today"
    const val CALENDAR = "calendar"
    const val CHAT = "chat"
    const val EXPLORE = "explore"
    const val GROWTH_PORTRAIT = "growth_portrait"
    const val HIGHLIGHTS = "highlights"
    const val LITTLE_JOYS = "little_joys"
    const val DIARY_EDITOR = "diary_editor/{date}?diary_id={diary_id}"
    const val TODO_CREATE = "todo_create/{date}"
    const val DIARY_SEARCH = "diary_search"
    const val PROFILE = "profile"
    const val EDIT_PROFILE = "edit_profile"
    const val TEST_HUB = "test_hub"
    const val TEST = "test/{test_id}"
    const val TEST_RESULT = "test_result/{test_id}"
    const val DRAWING = "drawing_flow?diary_id={diary_id}&prompt={prompt}"
    const val DRAWING_RESULT = "drawing_result/{image_url}?diary_id={diary_id}"
    const val RELAX = "relax"
    const val WHITE_NOISE = "white_noise"
    const val LIVE2D = "live2d"
    const val RECHARGE = "recharge"

    fun diaryEditor(date: LocalDate, diaryId: Long? = null): String {
        return if (diaryId != null) {
            "diary_editor/$date?diary_id=$diaryId"
        } else {
            "diary_editor/$date"
        }
    }

    fun todoCreate(date: LocalDate): String = "todo_create/$date"

    fun test(testId: String): String = "test_graph/$testId"
    fun testResult(testId: String): String = "test_result/$testId"

    fun drawing(diaryId: Long? = null, prompt: String? = null): String {
        val diaryArg = diaryId?.toString() ?: "null"
        val promptArg = prompt
            ?.takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it, "UTF-8") }
            ?: ""
        return "drawing_flow?diary_id=$diaryArg&prompt=$promptArg"
    }

    fun drawingResult(imageUrl: String, diaryId: Long? = null): String {
        val encoded = URLEncoder.encode(imageUrl, "UTF-8")
        return if (diaryId != null) {
            "drawing_result/$encoded?diary_id=$diaryId"
        } else {
            "drawing_result/$encoded"
        }
    }
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    sessionManager: SessionManager
) {
    val context = LocalContext.current
    val navDebugLog = remember { java.io.File(context.filesDir, "debug_nav.log") }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val id = activity?.intent?.getLongExtra("memory_diary_id", -1) ?: -1
        val day = activity?.intent?.getStringExtra("memory_diary_date")
        if (id > 0 && day != null && sessionManager.isLoggedIn()) {
            activity?.intent?.removeExtra("memory_diary_id")
            activity?.intent?.removeExtra("memory_diary_date")
            runCatching { navController.navigate(Routes.diaryEditor(LocalDate.parse(day), id)) }
        }
    }

    val protectedRoutes = listOf(
        Routes.TODAY,
        Routes.CALENDAR,
        Routes.CHAT,
        Routes.EXPLORE,
        Routes.GROWTH_PORTRAIT,
        Routes.HIGHLIGHTS,
        Routes.LITTLE_JOYS,
        Routes.DIARY_EDITOR,
        Routes.TODO_CREATE,
        Routes.DIARY_SEARCH,
        Routes.PROFILE,
        Routes.EDIT_PROFILE,
        Routes.TEST_HUB,
        Routes.TEST,
        Routes.TEST_RESULT,
        Routes.DRAWING,
        Routes.DRAWING_RESULT,
        Routes.WHITE_NOISE,
        Routes.RELAX,
        Routes.LIVE2D,
    )

    LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute != Routes.LOGIN && currentRoute != Routes.REGISTER) {
            val isProtectedRoute = protectedRoutes.any { route ->
                currentRoute.startsWith(route.substringBefore("{"))
            }

            if (isProtectedRoute && !sessionManager.isLoggedIn()) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
    // #region agent debug log - 21cff5
    fun logNavEvent(message: String, data: Map<String, Any?> = emptyMap()) {
        try {
            val logFile = navDebugLog
            val dataStr = data.entries.joinToString(",") { "${it.key}=${it.value}" }
            val entry = "{\"id\":\"log_${System.currentTimeMillis()}\",\"timestamp\":${System.currentTimeMillis()},\"location\":\"NavGraph.kt\",\"message\":\"$message\",\"data\":{$dataStr},\"hypothesisId\":\"H1\",\"runId\":\"debug-run-1\"}\n"
            logFile.appendText(entry)
        } catch (_: Exception) {}
    }
    // #endregion

    composable(Routes.LOGIN) {
        logNavEvent("Navigating to LOGIN", mapOf("route" to Routes.LOGIN))
        LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER) {
                        popUpTo(Routes.LOGIN) { inclusive = false }
                    }
                },
                onLoginSuccess = {
                    navController.navigate(Routes.TODAY) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = {
                    navController.popBackStack()
                },
                onRegisterSuccess = {
                    navController.navigate(Routes.TODAY) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.TODAY) {
            TodayScreen(
                onOpenAssistant = { navController.navigate(Routes.CHAT) },
                onOpenRecords = { navController.navigate(Routes.CALENDAR) },
                onOpenDiary = { id -> navController.navigate(Routes.diaryEditor(LocalDate.now(), id)) }
            )
        }
        composable(Routes.CALENDAR) { backStackEntry ->
            val calendarViewModel: CalendarViewModel = hiltViewModel()
            val shouldRefresh = backStackEntry.savedStateHandle.get<Boolean>("calendar_refresh") == true
            if (shouldRefresh) {
                backStackEntry.savedStateHandle.remove<Boolean>("calendar_refresh")
                LaunchedEffect(Unit) {
                    calendarViewModel.reloadSelectedDateData()
                }
            }
            // #region agent debug log - 21cff5
            logNavEvent("CalendarScreen loaded", mapOf(
                "shouldRefresh" to shouldRefresh,
                "startDestination" to startDestination
            ))
            // #endregion
            CalendarScreen(
                viewModel = calendarViewModel,
                onDateSelected = { date ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("calendar_selected_date", date.toString())
                },
                onDiaryClick = { diaryId, date ->
                    // #region agent debug log - 21cff5
                    logNavEvent("CalendarScreen onDiaryClick", mapOf(
                        "diaryId" to diaryId,
                        "date" to date.toString()
                    ))
                    // #endregion
                    val route = Routes.diaryEditor(date, diaryId)
                    Log.d(TAG, "Navigating to diary editor: diaryId=$diaryId, date=$date, route=$route")
                    navController.navigate(route)
                }
            )
        }

        // Compatibility for old in-app links: task management now lives in Today.
        composable(Routes.TODO_CREATE, arguments = listOf(navArgument("date") { type = NavType.StringType })) {
            LaunchedEffect(Unit) { navController.navigate(Routes.TODAY) { popUpTo(Routes.TODO_CREATE) { inclusive = true }; launchSingleTop = true } }
        }

        composable(Routes.DIARY_SEARCH) {
            DiarySearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDiary = { diary ->
                    val route = Routes.diaryEditor(diary.date, diary.id)
                    navController.navigate(route)
                }
            )
        }

        composable(Routes.CHAT) {
            val userId = sessionManager.getUserId()
            if (userId != null) {
                ChatScreen(
                    userId = userId,
                    onNavigateToLive2D = {
                        navController.navigate(Routes.LIVE2D)
                    },
                    onNavigateToRecharge = {
                        navController.navigate(Routes.RECHARGE)
                    },
                    onClientAction = { action ->
                        when (action.action) {
                            "open_relax", "open_music", "open_white_noise", "play_white_noise" -> navController.navigate(Routes.RELAX)
                            "open_test_hub", "open_test_history" -> navController.navigate(Routes.TEST_HUB)
                            "open_test" -> {
                                val testId = action.payload["test_id"]
                                if (testId.isNullOrBlank()) {
                                    navController.navigate(Routes.TEST_HUB)
                                } else {
                                    navController.navigate(Routes.test(testId))
                                }
                            }
                            "open_drawing" -> {
                                val diaryId = action.payload["diary_id"]?.toLongOrNull()
                                val prompt = action.payload["prompt"]
                                navController.navigate(Routes.drawing(diaryId, prompt))
                            }
                            "open_diary_editor" -> {
                                val diaryId = action.payload["diary_id"]?.toLongOrNull()
                                val dateStr = action.payload["diary_date"]
                                if (dateStr != null) {
                                    val route = if (diaryId != null) {
                                        "diary_editor/$dateStr?diary_id=$diaryId"
                                    } else {
                                        "diary_editor/$dateStr"
                                    }
                                    navController.navigate(route)
                                } else {
                                    navController.navigate(Routes.diaryEditor(LocalDate.now(), diaryId))
                                }
                            }
                            "open_todo_create", "open_today" -> navController.navigate(Routes.TODAY) { launchSingleTop = true }

                        }
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }

        composable(Routes.EXPLORE) {
            ExploreScreen(
                onNavigateToGrowthPortrait = {
                    navController.navigate(Routes.GROWTH_PORTRAIT)
                },
                onNavigateToHighlights = {
                    navController.navigate(Routes.HIGHLIGHTS)
                },
                onNavigateToLittleJoys = {
                    navController.navigate(Routes.LITTLE_JOYS)
                },
                onNavigateToDiary = { diaryId ->
                    val route = Routes.diaryEditor(LocalDate.now(), diaryId)
                    Log.d(TAG, "Navigate to diary: diaryId=$diaryId, route=$route")
                    navController.navigate(route)
                }
            )
        }

        composable(Routes.GROWTH_PORTRAIT) {
            GrowthPortraitScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.HIGHLIGHTS) {
            HighlightsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDiary = { diaryId ->
                    val route = Routes.diaryEditor(LocalDate.now(), diaryId)
                    navController.navigate(route)
                }
            )
        }

        composable(Routes.LITTLE_JOYS) {
            LittleJoysScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDiary = { diaryId ->
                    val route = Routes.diaryEditor(LocalDate.now(), diaryId)
                    navController.navigate(route)
                }
            )
        }

        composable(
            route = Routes.DIARY_EDITOR,
            arguments = listOf(
                navArgument("date") { type = NavType.StringType },
                navArgument("diary_id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val dateString = backStackEntry.arguments?.getString("date")
            val date = dateString?.let { LocalDate.parse(it) } ?: LocalDate.now()
            val diaryIdString = backStackEntry.arguments?.getString("diary_id")
            val diaryId = if (diaryIdString.isNullOrEmpty() || diaryIdString == "null") {
                null
            } else {
                diaryIdString.toLongOrNull()
            }

        // #region agent debug log - 21cff5
        logNavEvent("DIARY_EDITOR composable entering", mapOf(
            "dateString" to dateString,
            "diaryIdString" to diaryIdString,
            "date" to (dateString ?: "null"),
            "diaryId" to (diaryIdString?.toLongOrNull() ?: -1L)
        ))
        // #endregion

        Log.d(TAG, "DiaryEditorScreen params: dateString=$dateString, diaryIdString=$diaryIdString, date=$date, diaryId=$diaryId")

        DiaryEditorScreen(
                date = date,
                diaryId = diaryId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDrawing = { savedDiaryId ->
                    navController.navigate("drawing_flow?diary_id=${savedDiaryId ?: "null"}")
                },
                backStackEntry = backStackEntry
            )
        }

        composable(Routes.PROFILE) { backStackEntry ->
            val shouldRefresh = backStackEntry.savedStateHandle.get<Boolean>("refresh") == true
            if (shouldRefresh) {
                backStackEntry.savedStateHandle.remove<Boolean>("refresh")
            }

            ProfileScreen(
                onNavigateToSearchSettings = { navController.navigate(Routes.RECHARGE) },
                shouldRefresh = shouldRefresh,
                onNavigateToEditProfile = {
                    navController.navigate(Routes.EDIT_PROFILE)
                },

                onNavigateToGrowthPortrait = {
                    navController.navigate(Routes.GROWTH_PORTRAIT)
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.EDIT_PROFILE) {
            EditProfileScreen(
                onNavigateBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set("refresh", true)
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.TEST_HUB) {
            TestHubScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToTest = { testId ->
                    navController.navigate(Routes.test(testId))
                }
            )
        }

        navigation(
            startDestination = "test_start/{test_id}",
            route = "test_graph/{test_id}",
            arguments = listOf(
                navArgument("test_id") { type = NavType.StringType }
            )
        ) {
            composable(
                route = "test_start/{test_id}",
                arguments = listOf(
                    navArgument("test_id") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val testId = backStackEntry.arguments?.getString("test_id") ?: ""
                val parentRoute = "test_graph/$testId"
                val parentEntry = remember(backStackEntry, testId) {
                    navController.getBackStackEntry(parentRoute)
                }
                TestScreen(
                    testId = testId,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToResult = { id ->
                        navController.navigate("test_result/$id") {
                        }
                    },
                    viewModel = hiltViewModel(parentEntry)
                )
            }

            composable(
                route = "test_result/{test_id}",
                arguments = listOf(
                    navArgument("test_id") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val testId = backStackEntry.arguments?.getString("test_id") ?: ""
                val parentRoute = "test_graph/$testId"
                val parentEntry = remember(backStackEntry, testId) {
                    navController.getBackStackEntry(parentRoute)
                }
                TestResultScreen(
                    testId = testId,
                    onNavigateBack = {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(parentRoute) { inclusive = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onRetakeTest = {
                        navController.navigate(Routes.test(testId)) {
                            popUpTo(parentRoute) { inclusive = true }
                        }
                    },
                    viewModel = hiltViewModel(parentEntry)
                )
            }
        }

        // 绘画流程导航子图 (SharedDrawingViewModel)
        navigation(
            startDestination = "drawing_start",
            route = Routes.DRAWING,
            arguments = listOf(
                navArgument("diary_id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("prompt") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            composable("drawing_start") { startEntry ->
                val parentEntry = remember(startEntry) {
                    navController.getBackStackEntry(Routes.DRAWING)
                }
                val diaryIdString = parentEntry.arguments?.getString("diary_id")
                val diaryId = diaryIdString?.toLongOrNull()
                val initialPrompt = parentEntry.arguments
                    ?.getString("prompt")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                val sharedViewModel: SharedDrawingViewModel = hiltViewModel(parentEntry)

                DrawingScreen(
                    diaryId = diaryId,
                    initialPrompt = initialPrompt,
                    onNavigateBack = {
                        // 将保存的媒体通过 savedStateHandle 传递给 DiaryEditorScreen
                        val pendingMedia = sharedViewModel.getPendingMedia()
                        if (pendingMedia.isNotEmpty()) {
                            val editorEntry = navController.getBackStackEntry(Routes.DIARY_EDITOR)
                            val data = pendingMedia.map { "${it.type}:${it.url}" }
                            editorEntry.savedStateHandle["drawing_saved_media"] = data
                            sharedViewModel.clearSavedMediaItems()
                        }
                        navController.popBackStack()
                    },
                    onNavigateToResult = { imageUrl, savedDiaryId ->
                        navController.navigate("drawing_result/${URLEncoder.encode(imageUrl, "UTF-8")}?diary_id=${savedDiaryId ?: "null"}")
                    },
                    sharedViewModel = sharedViewModel,
                    viewModel = hiltViewModel(parentEntry),
                )
            }

            composable(
                route = "drawing_result/{image_url}?diary_id={diary_id}",
                arguments = listOf(
                    navArgument("image_url") { type = NavType.StringType },
                    navArgument("diary_id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { resultEntry ->
                val encodedUrl = resultEntry.arguments?.getString("image_url") ?: ""
                val imageUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                val drawingFlowRoute = Routes.DRAWING

                val drawingParentEntry = remember(resultEntry) {
                    navController.getBackStackEntry(drawingFlowRoute)
                }
                val resultViewModel: SharedDrawingViewModel = hiltViewModel(drawingParentEntry)

                DrawingResultScreen(
                    imageUrl = imageUrl,
                    sharedViewModel = resultViewModel,
                    viewModel = hiltViewModel(drawingParentEntry),
                    onNavigateBack = {
                        val savedItems = resultViewModel.consumeSavedMediaItems()
                        val editorEntry = runCatching {
                            navController.getBackStackEntry(Routes.DIARY_EDITOR)
                        }.getOrNull()
                        if (savedItems.isNotEmpty() && editorEntry != null) {
                            val data = savedItems.map { "${it.type}:${it.url}" }
                            editorEntry.savedStateHandle["drawing_saved_media"] = data
                        }
                        resultViewModel.clearSavedMediaItems()
                        if (editorEntry != null) {
                            navController.popBackStack(drawingFlowRoute, inclusive = true)
                        } else {
                            navController.navigate(Routes.TODAY) {
                                popUpTo(drawingFlowRoute) { inclusive = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onNavigateToCalendar = {
                        resultViewModel.clearSavedMediaItems()
                        navController.navigate(Routes.TODAY) {
                            popUpTo(drawingFlowRoute) { inclusive = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToDrawingList = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(Routes.RELAX) {
            RelaxScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.WHITE_NOISE) {
            RelaxScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.LIVE2D) {
            Live2DScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.RECHARGE) {
            RechargeScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
