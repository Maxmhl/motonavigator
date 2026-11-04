package de.tetnavigator.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farb-Tokens aus Spezifikation Abschnitt 5. */
@Immutable
data class TetColors(
    val bg: Color,
    val mapBg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val contour: Color,
    val text: Color,
    val sand: Color,
    val moss: Color,
    val accent: Color,
    val warning: Color,
    val danger: Color,
    val onAccent: Color,
    /** Untergrund-Farben (Sektions-Details, Track-Färbung). */
    val surfaceGravel: Color,
    val surfaceDirt: Color,
    val surfaceAsphalt: Color,
    val surfaceUnknown: Color,
    /** Gefahrener Track-Teil (gestrichelt braun). */
    val riddenTrack: Color,
    val startGreen: Color,
    val isDark: Boolean,
)

val DarkTetColors = TetColors(
    bg = Color(0xFF121310),
    mapBg = Color(0xFF171914),
    surface = Color(0xFF1E201B),
    surfaceRaised = Color(0xFF2A2D25),
    outline = Color(0xFF3A3D33),
    contour = Color(0xFF262920),
    text = Color(0xFFEDE6D6),
    sand = Color(0xFFC9B48A),
    moss = Color(0xFF6E7A4E),
    accent = Color(0xFFFF6B1A),
    warning = Color(0xFFF2C230),
    danger = Color(0xFFE5402F),
    onAccent = Color(0xFF121310),
    surfaceGravel = Color(0xFFC9B48A),
    surfaceDirt = Color(0xFF7A5A3E),
    surfaceAsphalt = Color(0xFF6B6E66),
    surfaceUnknown = Color(0xFF3A3D33),
    riddenTrack = Color(0xFF8A6A4A),
    startGreen = Color(0xFF6E9A4E),
    isDark = true,
)

/** Tag-Modus (Phase 2): helles Sand-Thema mit hohem Kontrast für direkte Sonne. */
val DayTetColors = TetColors(
    bg = Color(0xFFEDE5D0),
    mapBg = Color(0xFFE6DCC4),
    surface = Color(0xFFF7F1E3),
    surfaceRaised = Color(0xFFDDD2B6),
    outline = Color(0xFF7D7258),
    contour = Color(0xFFCFC3A5),
    text = Color(0xFF14130F),
    sand = Color(0xFF4F4430),
    moss = Color(0xFF4A5A28),
    accent = Color(0xFFD94E00),
    warning = Color(0xFFE0A800),
    danger = Color(0xFFC42B1C),
    onAccent = Color(0xFF121310),
    surfaceGravel = Color(0xFFB39A63),
    surfaceDirt = Color(0xFF7A5A3E),
    surfaceAsphalt = Color(0xFF55584F),
    surfaceUnknown = Color(0xFF9C927A),
    riddenTrack = Color(0xFF6E5034),
    startGreen = Color(0xFF3F7A2A),
    isDark = false,
)

val LocalTetColors = staticCompositionLocalOf { DarkTetColors }

/** Handschuh-Modus: vergrößert Touch-Flächen zusätzlich und schaltet Wischgesten ab. */
val LocalGloveMode = staticCompositionLocalOf { true }

object TetTheme {
    val colors: TetColors @Composable get() = LocalTetColors.current
    val type: TetTypography @Composable get() = LocalTetTypography.current
}

@Composable
fun TetTheme(dayMode: Boolean = false, gloveMode: Boolean = true, content: @Composable () -> Unit) {
    val c = if (dayMode) DayTetColors else DarkTetColors
    val scheme = if (c.isDark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.sand, onSecondary = c.onAccent,
            background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.sand, outline = c.outline,
            error = c.danger, onError = c.onAccent,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.sand, onSecondary = c.surface,
            background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.sand, outline = c.outline,
            error = c.danger, onError = c.surface,
        )
    }
    CompositionLocalProvider(
        LocalTetColors provides c,
        LocalTetTypography provides DefaultTetTypography,
        LocalGloveMode provides gloveMode,
    ) {
        MaterialTheme(colorScheme = scheme, typography = DefaultTetTypography.toMaterial(), content = content)
    }
}
