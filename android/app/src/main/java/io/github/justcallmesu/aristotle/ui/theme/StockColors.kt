package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The stock up/down palette (spec Q91, stocks-notes §2 contrast table): green / red for price and P/L changes only, not
 * M3 slots, so nothing else in the app can pick them up. Text contrast ≥ 5.4:1 on `surface` and `surfaceContainerLow` in
 * both themes. Every change also carries an arrow and a sign: colour is never the only cue. Red keeps meaning "error"
 * everywhere else (design.md §1).
 */
@Immutable
class StockColors(
    val gain: Color,
    val loss: Color,
    val flat: Color,
    val gainContainer: Color,
    val onGainContainer: Color,
    val lossContainer: Color,
    val onLossContainer: Color,
    val flatContainer: Color,
    val onFlatContainer: Color,
)

internal val StockColorsLight = StockColors(
    gain = Color(0xFF1F6A2E),
    loss = Color(0xFFB0281E),
    flat = Color(0xFF7A5600),
    gainContainer = Color(0xFFCFE8C4),
    onGainContainer = Color(0xFF0F3514),
    lossContainer = Color(0xFFF7D9D2),
    onLossContainer = Color(0xFF5A110A),
    flatContainer = Color(0xFFF0E0B0),
    onFlatContainer = Color(0xFF3A2A00),
)

internal val StockColorsDark = StockColors(
    gain = Color(0xFF8CD49A),
    loss = Color(0xFFFFA99C),
    flat = Color(0xFFE9C46A),
    gainContainer = Color(0xFF1F4A26),
    onGainContainer = Color(0xFFC2EFC6),
    lossContainer = Color(0xFF7A2016),
    onLossContainer = Color(0xFFFFDAD4),
    flatContainer = Color(0xFF4D3D0F),
    onFlatContainer = Color(0xFFF6E2A8),
)

internal val LocalStockColors = staticCompositionLocalOf { StockColorsLight }

/** The stock palette for the theme on screen; read like `MaterialTheme.colorScheme`, on stock values only. */
val AristotleStockColors: StockColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStockColors.current
