package com.mavislibrary.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PaperColors = lightColorScheme(
    primary = Color(0xFF7A3E1D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3D9C4),
    onPrimaryContainer = Color(0xFF2E1406),
    secondary = Color(0xFF5E5A3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E2BE),
    onSecondaryContainer = Color(0xFF1D1B05),
    tertiary = Color(0xFF3F5F55),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC6E5D9),
    onTertiaryContainer = Color(0xFF002019),
    background = Color(0xFFF6EFE2),
    onBackground = Color(0xFF2A211A),
    surface = Color(0xFFF6EFE2),
    onSurface = Color(0xFF2A211A),
    surfaceVariant = Color(0xFFEADFCC),
    onSurfaceVariant = Color(0xFF51463A),
    surfaceContainerLowest = Color(0xFFFFFBF4),
    surfaceContainerLow = Color(0xFFF9F2E6),
    surfaceContainer = Color(0xFFF1E8D8),
    surfaceContainerHigh = Color(0xFFEBE1CF),
    surfaceContainerHighest = Color(0xFFE5DAC6),
    outline = Color(0xFF84766A),
    outlineVariant = Color(0xFFD6C8B5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val MidnightColors = darkColorScheme(
    primary = Color(0xFFE2BF6A),
    onPrimary = Color(0xFF3B2B00),
    primaryContainer = Color(0xFF574316),
    onPrimaryContainer = Color(0xFFFFE08F),
    secondary = Color(0xFFB7C4DD),
    onSecondary = Color(0xFF213044),
    secondaryContainer = Color(0xFF2B3A52),
    onSecondaryContainer = Color(0xFFD4E2FA),
    tertiary = Color(0xFF9FCFC2),
    onTertiary = Color(0xFF02372E),
    tertiaryContainer = Color(0xFF1F4D44),
    onTertiaryContainer = Color(0xFFBBEBDE),
    background = Color(0xFF0D1220),
    onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF0D1220),
    onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF262C3B),
    onSurfaceVariant = Color(0xFFC3C4CC),
    surfaceContainerLowest = Color(0xFF080C16),
    surfaceContainerLow = Color(0xFF131927),
    surfaceContainer = Color(0xFF171E2D),
    surfaceContainerHigh = Color(0xFF1E2535),
    surfaceContainerHighest = Color(0xFF282F40),
    outline = Color(0xFF8D909A),
    outlineVariant = Color(0xFF3A4050),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val EditorialColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFFD7263D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDADC),
    onSecondaryContainer = Color(0xFF40000C),
    tertiary = Color(0xFF1B4DDB),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE1FF),
    onTertiaryContainer = Color(0xFF001551),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFDDDDDD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val KidsColors = lightColorScheme(
    primary = Color(0xFF1565E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD43B),
    onPrimaryContainer = Color(0xFF2B2100),
    secondary = Color(0xFFE53935),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF410001),
    tertiary = Color(0xFF2E9E44),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8F1BF),
    onTertiaryContainer = Color(0xFF002109),
    background = Color(0xFFFFFBEA),
    onBackground = Color(0xFF1C1B16),
    surface = Color(0xFFFFFBEA),
    onSurface = Color(0xFF1C1B16),
    surfaceVariant = Color(0xFFE3F0FF),
    onSurfaceVariant = Color(0xFF2B3A55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF7D6),
    surfaceContainer = Color(0xFFFFF2C2),
    surfaceContainerHigh = Color(0xFFFFEDAD),
    surfaceContainerHighest = Color(0xFFFFE795),
    outline = Color(0xFF5C6B85),
    outlineVariant = Color(0xFFC3D2EA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Look-specific layout tokens beyond Material's colour/type/shape. */
@Immutable
data class LookTokens(
    val look: Look,
    val isDark: Boolean,
    val coverMinWidth: Dp,
    val gridSpacing: Dp,
    val minTouchTarget: Dp,
    val showCoverCaptions: Boolean,
)

val LocalLookTokens = staticCompositionLocalOf { tokensFor(Look.PAPER) }

fun colorSchemeFor(look: Look): ColorScheme = when (look) {
    Look.PAPER -> PaperColors
    Look.MIDNIGHT -> MidnightColors
    Look.EDITORIAL -> EditorialColors
    Look.KIDS -> KidsColors
}

fun typographyFor(look: Look): Typography = when (look) {
    Look.PAPER -> PaperTypography
    Look.MIDNIGHT -> MidnightTypography
    Look.EDITORIAL -> EditorialTypography
    Look.KIDS -> KidsTypography
}

fun shapesFor(look: Look): Shapes = when (look) {
    Look.PAPER -> Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(16.dp),
    )
    Look.MIDNIGHT -> Shapes(
        extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )
    Look.EDITORIAL -> Shapes(
        extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(2.dp),
        large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(8.dp),
    )
    Look.KIDS -> Shapes(
        extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp), medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(32.dp), extraLarge = RoundedCornerShape(40.dp),
    )
}

fun tokensFor(look: Look): LookTokens = when (look) {
    Look.PAPER -> LookTokens(look, isDark = false, coverMinWidth = 112.dp, gridSpacing = 14.dp, minTouchTarget = 48.dp, showCoverCaptions = true)
    Look.MIDNIGHT -> LookTokens(look, isDark = true, coverMinWidth = 112.dp, gridSpacing = 16.dp, minTouchTarget = 48.dp, showCoverCaptions = true)
    Look.EDITORIAL -> LookTokens(look, isDark = false, coverMinWidth = 160.dp, gridSpacing = 20.dp, minTouchTarget = 48.dp, showCoverCaptions = true)
    Look.KIDS -> LookTokens(look, isDark = false, coverMinWidth = 180.dp, gridSpacing = 24.dp, minTouchTarget = 72.dp, showCoverCaptions = true)
}

@Composable
fun MavisTheme(look: Look, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLookTokens provides tokensFor(look)) {
        MaterialTheme(
            colorScheme = colorSchemeFor(look),
            typography = typographyFor(look),
            shapes = shapesFor(look),
            content = content,
        )
    }
}
