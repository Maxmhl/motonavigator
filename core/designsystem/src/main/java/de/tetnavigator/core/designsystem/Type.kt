package de.tetnavigator.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Eingebettete Schriften (res/font) – keine Google-Fonts-Downloads. */
val SairaStencil = FontFamily(Font(R.font.saira_stencil_one, FontWeight.Normal))
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

@Immutable
data class TetTypography(
    /** Stencil-Titel 28–32 sp. */
    val title: TextStyle,
    /** Stencil-Tasten 17–22 sp. */
    val button: TextStyle,
    /** Stencil-Mini-Labels 12–13 sp. */
    val label: TextStyle,
    /** Barlow Fließtext 15–20 sp. */
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val bodySmall: TextStyle,
    /** Barlow Bold Zahlen 28–46 sp. */
    val numberL: TextStyle,
    val numberM: TextStyle,
)

val DefaultTetTypography = TetTypography(
    title = TextStyle(fontFamily = SairaStencil, fontSize = 30.sp, letterSpacing = 0.5.sp),
    button = TextStyle(fontFamily = SairaStencil, fontSize = 19.sp, letterSpacing = 0.5.sp),
    label = TextStyle(fontFamily = SairaStencil, fontSize = 12.5.sp, letterSpacing = 0.8.sp),
    body = TextStyle(fontFamily = BarlowCondensed, fontSize = 18.sp),
    bodyStrong = TextStyle(fontFamily = BarlowCondensed, fontSize = 20.sp, fontWeight = FontWeight.Bold),
    bodySmall = TextStyle(fontFamily = BarlowCondensed, fontSize = 15.sp),
    numberL = TextStyle(fontFamily = BarlowCondensed, fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    numberM = TextStyle(fontFamily = BarlowCondensed, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
)

val LocalTetTypography = staticCompositionLocalOf { DefaultTetTypography }

internal fun TetTypography.toMaterial() = Typography(
    displayLarge = title, headlineMedium = title, titleLarge = title.copy(fontSize = 24.sp),
    titleMedium = bodyStrong, bodyLarge = body, bodyMedium = body, bodySmall = bodySmall,
    labelLarge = button, labelMedium = label, labelSmall = label,
)
