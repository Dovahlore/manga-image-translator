package com.mit.reader.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

sealed class Route(val path: String) {
    data object Library : Route("library")
    data object Reader : Route("reader/{bookId}") {
        fun of(bookId: String) = "reader/$bookId"
    }
    data object Settings : Route("settings")
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Route.Library.path) {
        composable(Route.Library.path) {
            LibraryScreen(
                onOpen = { id -> nav.navigate(Route.Reader.of(id)) },
                onSettings = { nav.navigate(Route.Settings.path) },
            )
        }
        composable(Route.Reader.path) { backStack ->
            val bookId = backStack.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
        composable(Route.Settings.path) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
