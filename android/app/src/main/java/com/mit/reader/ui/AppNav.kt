package com.mit.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mit.reader.ReaderApp

sealed class Route(val path: String) {
    data object Library : Route("library")
    data object Reader : Route("reader/{bookId}") {
        fun of(bookId: String) = "reader/$bookId"
    }
    data object Settings : Route("settings")
    data object Kmoe : Route("kmoe")
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ReaderApp

    // 外部「打开」epub/mobi 导入完成后，自动进阅读器
    LaunchedEffect(app.pendingOpenBookId) {
        val id = app.pendingOpenBookId ?: return@LaunchedEffect
        nav.navigate(Route.Reader.of(id)) { launchSingleTop = true }
        app.consumePendingOpen()
    }

    NavHost(navController = nav, startDestination = Route.Library.path) {
        composable(Route.Library.path) {
            LibraryScreen(
                onOpen = { id -> nav.navigate(Route.Reader.of(id)) },
                onSettings = { nav.navigate(Route.Settings.path) },
                onKmoe = { nav.navigate(Route.Kmoe.path) },
            )
        }
        composable(Route.Reader.path) { backStack ->
            val bookId = backStack.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
        composable(Route.Settings.path) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Route.Kmoe.path) {
            KmoeScreen(onBack = { nav.popBackStack() })
        }
    }
}
