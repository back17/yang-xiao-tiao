package com.skip.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = TerracottaLight,
    onPrimary = DarkCanvas,
    primaryContainer = ColorAliases.darkTerracottaContainer,
    onPrimaryContainer = DarkInk,
    secondary = SageLight,
    onSecondary = DarkCanvas,
    secondaryContainer = ColorAliases.darkSageContainer,
    onSecondaryContainer = DarkInk,
    tertiary = ColorAliases.darkOchre,
    background = DarkCanvas,
    onBackground = DarkInk,
    surface = DarkPaper,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceMuted,
    onSurfaceVariant = DarkInkMuted,
    outline = DarkInkMuted,
    outlineVariant = DarkOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = WarmPaper,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = Charcoal,
    secondary = Sage,
    onSecondary = WarmPaper,
    secondaryContainer = SageSoft,
    onSecondaryContainer = Charcoal,
    tertiary = Ochre,
    background = WarmCanvas,
    onBackground = Charcoal,
    surface = WarmPaper,
    onSurface = Charcoal,
    surfaceVariant = WarmSurfaceMuted,
    onSurfaceVariant = CharcoalMuted,
    outline = CharcoalMuted,
    outlineVariant = WarmOutline,
)

private object ColorAliases {
    val darkTerracottaContainer = androidx.compose.ui.graphics.Color(0xFF60382C)
    val darkSageContainer = androidx.compose.ui.graphics.Color(0xFF394936)
    val darkOchre = androidx.compose.ui.graphics.Color(0xFFD5AB6E)
}

private val DefaultTypography = Typography()
private val AppTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(letterSpacing = 0.sp),
    displayMedium = DefaultTypography.displayMedium.copy(letterSpacing = 0.sp),
    displaySmall = DefaultTypography.displaySmall.copy(letterSpacing = 0.sp),
    headlineLarge = DefaultTypography.headlineLarge.copy(letterSpacing = 0.sp),
    headlineMedium = DefaultTypography.headlineMedium.copy(letterSpacing = 0.sp),
    headlineSmall = DefaultTypography.headlineSmall.copy(letterSpacing = 0.sp),
    titleLarge = DefaultTypography.titleLarge.copy(letterSpacing = 0.sp),
    titleMedium = DefaultTypography.titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = DefaultTypography.titleSmall.copy(letterSpacing = 0.sp),
    bodyLarge = DefaultTypography.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = DefaultTypography.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = DefaultTypography.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = DefaultTypography.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = DefaultTypography.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = DefaultTypography.labelSmall.copy(letterSpacing = 0.sp),
)

@Composable
fun SkipTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        content = content,
    )
}
