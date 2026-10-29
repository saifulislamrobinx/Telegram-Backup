package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NothingDarkColorScheme = darkColorScheme(
    primary = NothingWhite,
    onPrimary = NothingBlack,
    primaryContainer = NothingElevated,
    onPrimaryContainer = NothingWhite,
    secondary = NothingGray,
    onSecondary = NothingBlack,
    secondaryContainer = NothingGraphite,
    onSecondaryContainer = NothingWhite,
    tertiary = NothingRed,
    onTertiary = NothingWhite,
    tertiaryContainer = NothingRedSoft,
    onTertiaryContainer = NothingWhite,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingGraphite,
    onSurface = NothingWhite,
    surfaceVariant = NothingElevated,
    onSurfaceVariant = NothingGray,
    outline = NothingBorder,
    outlineVariant = NothingDotMuted,
    error = NothingRed,
    onError = NothingWhite
)

private val NothingLightColorScheme = lightColorScheme(
    primary = NothingBlack,
    onPrimary = NothingWhite,
    primaryContainer = NothingLightElevated,
    onPrimaryContainer = NothingBlack,
    secondary = NothingLightGray,
    onSecondary = NothingWhite,
    secondaryContainer = NothingLightSurface,
    onSecondaryContainer = NothingBlack,
    tertiary = NothingRed,
    onTertiary = NothingWhite,
    tertiaryContainer = Color(0xFFFFE5E6),
    onTertiaryContainer = NothingRed,
    background = NothingLightBg,
    onBackground = NothingBlack,
    surface = NothingLightSurface,
    onSurface = NothingBlack,
    surfaceVariant = NothingLightElevated,
    onSurfaceVariant = NothingLightGray,
    outline = NothingLightBorder,
    outlineVariant = NothingLightBorder,
    error = NothingRed,
    onError = NothingWhite
)

val NothingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun NothingVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NothingDarkColorScheme else NothingLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = NothingShapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NothingVaultTheme(darkTheme = darkTheme, content = content)
}
