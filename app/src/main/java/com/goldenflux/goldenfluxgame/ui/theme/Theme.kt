package com.goldenflux.goldenfluxgame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GoldenFluxColors = darkColorScheme(
    primary = GfGold,
    onPrimary = GfBackgroundDeep,
    secondary = GfPurpleBright,
    onSecondary = GfTextPrimary,
    background = GfBackground,
    onBackground = GfTextPrimary,
    surface = GfPanel,
    onSurface = GfTextPrimary,
    surfaceVariant = GfPanelLight,
    onSurfaceVariant = GfTextDim,
)

@Composable
fun GoldenFluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GoldenFluxColors,
        typography = GfTypography,
        content = content,
    )
}
