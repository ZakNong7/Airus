package com.zaknong.airus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentSecondary,
    tertiary = AccentPrimary,
    background = BlackTrue,
    surface = BlackSurface,
    onPrimary = BlackTrue,
    onSecondary = BlackTrue,
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun AirusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Airus is primarily AMOLED black

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
