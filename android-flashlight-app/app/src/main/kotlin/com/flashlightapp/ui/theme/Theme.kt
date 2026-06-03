package com.flashlightapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary       = md_theme_dark_primary,
    onPrimary     = md_theme_dark_onPrimary,
    background    = md_theme_dark_background,
    surface       = md_theme_dark_surface,
    onBackground  = md_theme_dark_onBackground,
    onSurface     = md_theme_dark_onSurface
)

private val LightColorScheme = lightColorScheme(
    primary    = md_theme_light_primary,
    onPrimary  = md_theme_light_onPrimary,
    background = md_theme_light_background
)

@Composable
fun FlashlightAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AppTypography,
        content     = content
    )
}
