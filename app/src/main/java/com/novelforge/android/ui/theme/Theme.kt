package com.novelforge.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Studio Noir" – gedämpfte Indigo/Blau-Signatur, dunkle Tiefe (vom macOS-Original übernommen).
val NoirBackground = Color(0xFF0F0F15)
val NoirSurface = Color(0xFF1A1A23)
val NoirSurfaceElevated = Color(0xFF24242E)
val NoirHairline = Color(0x17FFFFFF)
val NoirPrimary = Color(0xFF6B73FF)   // Indigo-Akzent
val NoirSecondary = Color(0xFF8F80EB) // Violett
val NoirLime = Color(0xFF8BE36B)      // Erfolg
val NoirAmber = Color(0xFFE3B24A)     // Warnung
val NoirDanger = Color(0xFFE05A6B)    // Fehler
val NoirTextPrimary = Color(0xFFEDEDF2)
val NoirTextMuted = Color(0xFF9A9AAB)

private val NoirColors = darkColorScheme(
    primary = NoirPrimary,
    onPrimary = Color.White,
    secondary = NoirSecondary,
    onSecondary = Color.White,
    tertiary = NoirLime,
    background = NoirBackground,
    onBackground = NoirTextPrimary,
    surface = NoirSurface,
    onSurface = NoirTextPrimary,
    surfaceVariant = NoirSurfaceElevated,
    onSurfaceVariant = NoirTextMuted,
    error = NoirDanger,
    outline = NoirHairline,
)

@Composable
fun NovelForgeTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Bewusst immer dunkel – das ist die Markenidentität von NovelForge.
    MaterialTheme(
        colorScheme = NoirColors,
        typography = Typography(),
        content = content
    )
}
