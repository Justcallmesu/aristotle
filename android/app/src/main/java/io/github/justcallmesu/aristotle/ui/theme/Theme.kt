package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Palette A only, light/dark from the system. No dynamic colour (spec Q38). */
@Composable
fun AristotleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AristotleDarkColors else AristotleLightColors,
        typography = AristotleTypography,
        shapes = AristotleShapes,
        content = content,
    )
}
