package com.mit.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF90CAF9),
    background = Color(0xFF101014),
    surface = Color(0xFF1B1B20),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun MangaReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}
