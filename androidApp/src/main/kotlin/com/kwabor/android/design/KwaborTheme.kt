package com.kwabor.android.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = KwaborColors.Ink950,
    onPrimary = KwaborColors.Surface0,
    secondary = KwaborColors.Ink700,
    secondaryContainer = KwaborColors.Ink100,
    onSecondaryContainer = KwaborColors.Ink950,
    background = KwaborColors.Paper50,
    onBackground = KwaborColors.Ink900,
    surface = KwaborColors.Surface0,
    onSurface = KwaborColors.Ink900,
    surfaceContainer = KwaborColors.Surface0,
    surfaceVariant = KwaborColors.Ink100,
    onSurfaceVariant = KwaborColors.Ink700,
    outline = KwaborColors.Ink500,
    outlineVariant = KwaborColors.Ink200,
    error = KwaborColors.Ticket,
    onError = KwaborColors.Surface0,
)

private val DarkColors = darkColorScheme(
    primary = KwaborColors.DarkText,
    onPrimary = KwaborColors.Ink950,
    secondary = KwaborColors.Ink300,
    secondaryContainer = KwaborColors.DarkSurfaceRaised,
    onSecondaryContainer = KwaborColors.DarkText,
    background = KwaborColors.DarkBackground,
    onBackground = KwaborColors.DarkText,
    surface = KwaborColors.DarkSurface,
    onSurface = KwaborColors.DarkText,
    surfaceContainer = KwaborColors.DarkSurface,
    surfaceVariant = KwaborColors.DarkSurfaceRaised,
    onSurfaceVariant = KwaborColors.Ink300,
    outline = KwaborColors.Ink300,
    outlineVariant = KwaborColors.DarkOutline,
    error = KwaborColors.TicketDark,
    onError = KwaborColors.Ink950,
)

@Composable
fun KwaborTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KwaborTypography,
        content = content,
    )
}
