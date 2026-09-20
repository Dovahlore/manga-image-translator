package com.mit.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF90CAF9),
    background = Color.Black,
    surface = Color(0xFF1B1B20),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    background = Color.White,
    surface = Color(0xFFFFFFFF),
)

@Composable
fun MangaReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
