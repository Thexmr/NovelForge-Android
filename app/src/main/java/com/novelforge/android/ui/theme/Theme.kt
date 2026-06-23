package com.novelforge.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * "Studio Noir" – das Markendesign von NovelForge: tiefe Tinten-Schwärze, eine seriöse
 * Indigo-Leitfarbe und Champagner-Gold als edler Akzent. Bewusst reduziert und einheitlich,
 * damit die App hochwertig und wie ein digitales Verlagshaus wirkt.
 */
// ---- Marken-Palette (Single Source of Truth) ----------------------------------------
val InkBackground = Color(0xFF0C0D12)          // Tinte/Anthrazit – ruhige Tiefe
val InkSurface = Color(0xFF14151C)             // Flächen
val InkContainerLow = Color(0xFF15161E)
val InkContainer = Color(0xFF191B23)           // Karten
val InkContainerHigh = Color(0xFF23252F)       // erhöhte Karten
val InkContainerHighest = Color(0xFF2A2C38)
val InkHairline = Color(0xFF2C2F3C)            // feine Trennlinien

val Indigo = Color(0xFF6E78FF)                 // Leitfarbe – seriöses Indigo
val IndigoContainer = Color(0xFF262B57)
val Violet = Color(0xFF9A8CF0)                 // Sekundärakzent
val VioletContainer = Color(0xFF2B2750)

val NoirGold = Color(0xFFD3B373)               // Champagner-Gold – Premium-Akzent
val GoldContainer = Color(0xFF362C18)

val Success = Color(0xFF6FD39A)
val Danger = Color(0xFFE5687A)

val TextPrimary = Color(0xFFECEDF3)
val TextMuted = Color(0xFF9DA0B2)

// Aliase für ältere Referenzen (Abwärtskompatibilität)
val NoirPrimary = Indigo
val NoirSecondary = Violet

private val NoirColors = darkColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFFD7DAFF),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = VioletContainer,
    onSecondaryContainer = Color(0xFFE2DCFF),
    tertiary = NoirGold,
    onTertiary = Color(0xFF231A08),
    tertiaryContainer = GoldContainer,
    onTertiaryContainer = Color(0xFFF3E2BE),
    background = InkBackground,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkContainerHigh,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = InkBackground,
    surfaceContainerLow = InkContainerLow,
    surfaceContainer = InkContainer,
    surfaceContainerHigh = InkContainerHigh,
    surfaceContainerHighest = InkContainerHighest,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFF3A1820),
    onErrorContainer = Color(0xFFFFC9D1),
    outline = InkHairline,
    outlineVariant = Color(0xFF20222C),
    scrim = Color(0xFF000000),
)

// ---- Editoriale Typografie: Serif für Marke/Überschriften, klare Sans im Fließtext ---
private val brand = FontFamily.Serif
private val base = Typography()
private val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    displayMedium = base.displayMedium.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    displaySmall = base.displaySmall.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    headlineLarge = base.headlineLarge.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = brand, fontWeight = FontWeight.Medium),
)

@Composable
fun NovelForgeTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Bewusst immer dunkel – das ist die Markenidentität von NovelForge.
    MaterialTheme(
        colorScheme = NoirColors,
        typography = AppTypography,
        content = content
    )
}
