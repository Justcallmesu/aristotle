package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Whether the app draws dark: the user's theme choice (TS1), else the system's mode. Read this, not isSystemInDarkTheme(). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Palette A only, light or dark as [darkTheme] says (the user's choice, TS1, default the system's). No dynamic colour
 * (spec Q38). [textScale] is the app's text size (TS1): it multiplies the phone's font scale for this app's `sp` text
 * only, so every style (the M3 slots and [AristotleTextStyles]) grows or shrinks together; dp sizes stay.
 */
@Composable
fun AristotleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = if (textScale == 1f) density else Density(density.density, density.fontScale * textScale)
    CompositionLocalProvider(LocalDarkTheme provides darkTheme, LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = if (darkTheme) AristotleDarkColors else AristotleLightColors,
            typography = AristotleTypography,
            shapes = AristotleShapes,
            content = content,
        )
    }
}
