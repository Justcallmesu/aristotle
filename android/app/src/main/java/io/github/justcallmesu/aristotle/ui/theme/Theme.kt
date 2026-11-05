package io.github.justcallmesu.aristotle.ui.theme

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import io.github.justcallmesu.aristotle.BuildConfig
import io.github.justcallmesu.aristotle.data.TextSize
import kotlinx.coroutines.CoroutineExceptionHandler

/** Whether the app draws dark: the user's theme choice (TS1), else the system's mode. Read this, not isSystemInDarkTheme(). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** The factor a text size choice (TS1) puts on every style; `scaled` keeps the 11 sp floor. */
internal val TextSize.scale: Float
    get() = when (this) {
        TextSize.Small -> 0.9f
        TextSize.Default -> 1f
        TextSize.Large -> 1.15f
    }

/**
 * Palette A only, light or dark as [darkTheme] says (the user's choice, TS1, default the system's). No dynamic colour
 * (spec Q38). [textSize] scales the M3 slots and [AristotleTextStyles] themselves, never under 11 sp, on top of the
 * phone's font scale; dp sizes stay. Touch targets are Material's minimum interactive size, 48 dp (Q67).
 */
@Composable
fun AristotleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textSize: TextSize = TextSize.Default,
    content: @Composable () -> Unit,
) {
    val scale = textSize.scale
    val typography = remember(scale) { aristotleTypography(scale) }
    val styles = remember(scale) { AristotleStyles(scale) }
    val provided = mutableListOf<ProvidedValue<*>>(
        LocalDarkTheme provides darkTheme,
        LocalAristotleStyles provides styles,
        LocalStockColors provides if (darkTheme) StockColorsDark else StockColorsLight,
        LocalMinimumInteractiveComponentSize provides Sizes.minTouchTarget,
    )
    if (BuildConfig.DEBUG) provided += LocalFontFamilyResolver provides loggingFontResolver()
    CompositionLocalProvider(*provided.toTypedArray()) {
        MaterialTheme(
            colorScheme = if (darkTheme) AristotleDarkColors else AristotleLightColors,
            typography = typography,
            shapes = AristotleShapes,
            content = content,
        )
    }
}

/** Debug builds log a font that fails to load (the downloadable Literata) instead of falling back silently (TU1 G1). */
@Composable
private fun loggingFontResolver(): FontFamily.Resolver {
    val context = LocalContext.current
    return remember(context) {
        createFontFamilyResolver(context, CoroutineExceptionHandler { _, error -> Log.w("AristotleFonts", "font failed to load", error) })
    }
}
