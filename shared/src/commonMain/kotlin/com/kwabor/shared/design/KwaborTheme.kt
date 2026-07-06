package com.kwabor.shared.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = KwaborColors.Ink950,
    onPrimary = KwaborColors.Surface0,
    secondary = KwaborColors.Ink700,
    background = KwaborColors.Paper50,
    onBackground = KwaborColors.Ink900,
    surface = KwaborColors.Surface0,
    onSurface = KwaborColors.Ink900,
    error = KwaborColors.Ticket,
)

private val DarkColors = darkColorScheme(
    primary = KwaborColors.DarkText,
    onPrimary = KwaborColors.Ink950,
    secondary = KwaborColors.Ink300,
    background = KwaborColors.DarkBackground,
    onBackground = KwaborColors.DarkText,
    surface = KwaborColors.DarkSurface,
    onSurface = KwaborColors.DarkText,
    error = KwaborColors.Ticket,
)

@Composable
fun KwaborTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KwaborTypography,
        content = content,
    )
}
