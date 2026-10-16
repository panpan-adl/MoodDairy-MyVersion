package com.example.mydiary.navigation

import androidx.navigation.NavController

internal const val LIFE_SECTION_REQUEST = "life_section_request"

fun NavController.navigateMainTab(route: String) {
    navigate(route) {
        popUpTo(Routes.CHAT) { saveState = true }
        launchSingleTop = true
        // The assistant is the root; restoring a shortcut stack here can reopen
        // its child instead of the assistant itself.
        restoreState = route != Routes.CHAT
    }
    // A tab's saved stack can include destinations opened by assistant shortcuts.
    // Keep the tab's state, but bring its own page to the front on an explicit tap.
    if (currentDestination?.route != route && !popBackStack(route, inclusive = false)) {
        navigate(route) {
            popUpTo(Routes.CHAT)
            launchSingleTop = true
        }
    }
}

fun NavController.navigateToLife(section: String) {
    navigateMainTab(Routes.LIFE)
    currentBackStackEntry?.savedStateHandle?.set(LIFE_SECTION_REQUEST, section)
}
