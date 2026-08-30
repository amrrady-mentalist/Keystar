package com.notesapp.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Golden amber palette matching the primary accent color from screenshot (#EEA000)
val GoldenAmber = Color(0xFFEEA000)
val GoldenAmberLight = Color(0xFFFFB72B)
val BlobViolet = Color(0xFF8B7CFF)
val BlobRose = Color(0xFFFF7AA2)
val BlobTeal = Color(0xFF4FE8C4)
val BlobAmber = Color(0xFFFFC35A)
val AccentA = GoldenAmber
val AccentB = GoldenAmberLight
val Danger = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = AccentA,
    secondary = AccentB,
    error = Danger,
    background = Color.Black,
    surface = Color(0xFF121212)
)

private val LightColors = lightColorScheme(
    primary = AccentA,
    secondary = AccentB,
    error = Danger,
    background = Color.White,
    surface = Color(0xFFF5F5F5)
)

@Composable
fun NotesNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

/** Resolves a stored ThemeMode preference against the system setting when SYSTEM. */
@Composable
fun resolveDarkTheme(mode: com.notesapp.offline.data.ThemeMode): Boolean = when (mode) {
    com.notesapp.offline.data.ThemeMode.DARK -> true
    com.notesapp.offline.data.ThemeMode.LIGHT -> false
    com.notesapp.offline.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
}
