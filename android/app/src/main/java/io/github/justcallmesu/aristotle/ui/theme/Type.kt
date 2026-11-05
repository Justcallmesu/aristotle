package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.justcallmesu.aristotle.R

// docs/design.md §2: Atkinson Next and Mono are bundled (Q66: the Play-services provider does not serve them); Literata
// is a downloadable Google Font. Each family ends in a device fallback so text still renders if a font cannot load.

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val weights = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600)

private fun deviceFallback(name: String) = weights.map { Font(familyName = DeviceFontFamilyName(name), weight = it) }

private fun downloadableFamily(name: String, fallback: String): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(weights.map { Font(googleFont = font, fontProvider = fontProvider, weight = it) } + deviceFallback(fallback))
}

private fun bundledFamily(regular: Int, medium: Int, semiBold: Int, fallback: String) = FontFamily(
    listOf(Font(regular, FontWeight.W400), Font(medium, FontWeight.W500), Font(semiBold, FontWeight.W600)) + deviceFallback(fallback),
)

val LiterataFamily: FontFamily = downloadableFamily("Literata", "serif")
val AtkinsonFamily: FontFamily =
    bundledFamily(R.font.atkinson_next_regular, R.font.atkinson_next_medium, R.font.atkinson_next_semibold, "sans-serif")

// Mono 600 is bundled too: tool names are mono 600 (design.md §5 tool card); without it they would be faux-bold 500.
val AtkinsonMonoFamily: FontFamily =
    bundledFamily(R.font.atkinson_mono_regular, R.font.atkinson_mono_medium, R.font.atkinson_mono_semibold, "monospace")

/** design.md §1: the text size choice scales every style, but no text renders below this. */
val MinTextSize: TextUnit = 11.sp

/**
 * This style at [scale] (the app's text size, TS1), never under [MinTextSize]. Line height and tracking take the same
 * factor so the style keeps its proportions; a style the floor lifts grows by exactly what the floor needs.
 */
internal fun TextStyle.scaled(scale: Float): TextStyle {
    if (!fontSize.isSpecified) return this
    val factor = maxOf(scale, MinTextSize.value / fontSize.value)
    if (factor == 1f) return this
    return copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
        letterSpacing = if (letterSpacing.isSpecified) letterSpacing * factor else letterSpacing,
    )
}

private fun style(family: FontFamily, size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
    )

private val BaseTypography = Typography(
    headlineSmall = style(LiterataFamily, 24f, 32f, FontWeight.W500),
    titleLarge = style(LiterataFamily, 22f, 28f, FontWeight.W500),
    titleMedium = style(AtkinsonFamily, 16f, 24f, FontWeight.W600, 0.1f),
    titleSmall = style(AtkinsonFamily, 14f, 20f, FontWeight.W600, 0.1f),
    bodyLarge = style(AtkinsonFamily, 16f, 24f, FontWeight.W400),
    bodyMedium = style(AtkinsonFamily, 14f, 20f, FontWeight.W400),
    bodySmall = style(AtkinsonFamily, 12f, 16f, FontWeight.W400),
    labelLarge = style(AtkinsonFamily, 14f, 20f, FontWeight.W600, 0.1f),
    labelMedium = style(AtkinsonFamily, 12f, 16f, FontWeight.W600, 0.3f),
    labelSmall = style(AtkinsonFamily, 11f, 16f, FontWeight.W500, 0.3f),
)

/**
 * The M3 slots at the app's text size. MaterialTheme carries them into dialogs, sheets and menus too, which a density
 * or fontScale override does not reach (those windows bring their own density).
 */
internal fun aristotleTypography(scale: Float): Typography = with(BaseTypography) {
    copy(
        displayLarge = displayLarge.scaled(scale),
        displayMedium = displayMedium.scaled(scale),
        displaySmall = displaySmall.scaled(scale),
        headlineLarge = headlineLarge.scaled(scale),
        headlineMedium = headlineMedium.scaled(scale),
        headlineSmall = headlineSmall.scaled(scale),
        titleLarge = titleLarge.scaled(scale),
        titleMedium = titleMedium.scaled(scale),
        titleSmall = titleSmall.scaled(scale),
        bodyLarge = bodyLarge.scaled(scale),
        bodyMedium = bodyMedium.scaled(scale),
        bodySmall = bodySmall.scaled(scale),
        labelLarge = labelLarge.scaled(scale),
        labelMedium = labelMedium.scaled(scale),
        labelSmall = labelSmall.scaled(scale),
    )
}

/** Styles outside the M3 slots (design.md §2 table) at one text size. Screens use these, never ad-hoc sizes. */
@Immutable
class AristotleStyles internal constructor(scale: Float) {
    val wordmark: TextStyle = style(LiterataFamily, 22f, 28f, FontWeight.W600, 0.2f).scaled(scale)
    val wordmarkSmall: TextStyle = style(LiterataFamily, 18f, 24f, FontWeight.W600, 0.2f).scaled(scale)
    val codeInline: TextStyle = style(AtkinsonMonoFamily, 13f, 18f, FontWeight.W400).scaled(scale)
    val details: TextStyle = style(AtkinsonMonoFamily, 12f, 18f, FontWeight.W400).scaled(scale)
    val fingerprint: TextStyle = style(AtkinsonMonoFamily, 17f, 24f, FontWeight.W500, 0.5f).scaled(scale)
    val pairingCode: TextStyle = style(AtkinsonMonoFamily, 22f, 28f, FontWeight.W500, 1f).scaled(scale)
    val fingerprintShort: TextStyle = style(AtkinsonMonoFamily, 12f, 16f, FontWeight.W400).scaled(scale)

    /** The live status line of a session row ("Running · step N"): bodyMedium at 600. */
    val liveStatus: TextStyle = style(AtkinsonFamily, 14f, 20f, FontWeight.W600).scaled(scale)

    /** The chat top bar's subtitle while a turn runs: bodySmall at 600. */
    val liveStatusSmall: TextStyle = style(AtkinsonFamily, 12f, 16f, FontWeight.W600).scaled(scale)

    /** Chat top bar title (design.md §2: titleLarge at 18 / 24). */
    val chatTitle: TextStyle = style(LiterataFamily, 18f, 24f, FontWeight.W500).scaled(scale)

    /** Message text, user and assistant (design.md §2 "chat text"). */
    val chatText: TextStyle = style(AtkinsonFamily, 15f, 23f, FontWeight.W400).scaled(scale)

    /** Tool card header: the tool name, then its one-line argument summary. */
    val toolName: TextStyle = style(AtkinsonMonoFamily, 13f, 18f, FontWeight.W600).scaled(scale)
    val toolSummary: TextStyle = style(AtkinsonMonoFamily, 13f, 18f, FontWeight.W400).scaled(scale)

    /** Tool output and Markdown code blocks (design.md §2 "code tool output"). */
    val toolOutput: TextStyle = style(AtkinsonMonoFamily, 11.5f, 17f, FontWeight.W400).scaled(scale)

    /** Loading screen quote (Q45): English in Literata, the Greek below it smaller, then the citation. */
    val quote: TextStyle = style(LiterataFamily, 20f, 30f, FontWeight.W400).scaled(scale)
    val quoteGreek: TextStyle = style(LiterataFamily, 13f, 20f, FontWeight.W400).scaled(scale)
    val quoteCitation: TextStyle = style(AtkinsonFamily, 12f, 16f, FontWeight.W500, 0.3f).scaled(scale)

    // Stocks (stocks-notes §3, TS2). Numbers are tabular so columns of prices line up.
    /** A stock code: Mono 600 16 / 22. */
    val stockCode: TextStyle = style(AtkinsonMonoFamily, 16f, 22f, FontWeight.W600, 0.2f).scaled(scale)

    /** A code in a ticker chip or a search row: Mono 600 13 / 18. */
    val stockCodeSmall: TextStyle = style(AtkinsonMonoFamily, 13f, 18f, FontWeight.W600).scaled(scale)

    /** A last price in a row: 600 16 / 22, tabular. */
    val stockPrice: TextStyle = style(AtkinsonFamily, 16f, 22f, FontWeight.W600).scaled(scale).copy(fontFeatureSettings = "tnum")

    /** A change beside its arrow: 600 12 / 18, tabular. */
    val stockChange: TextStyle = style(AtkinsonFamily, 12f, 18f, FontWeight.W600).scaled(scale).copy(fontFeatureSettings = "tnum")

    /** The holdings value on Home: 600 20 / 28, tabular. */
    val stockValue: TextStyle = style(AtkinsonFamily, 20f, 28f, FontWeight.W600).scaled(scale).copy(fontFeatureSettings = "tnum")

    /** The holdings page's value and the detail page's price: 600 24 / 32, tabular. */
    val stockValueLarge: TextStyle = style(AtkinsonFamily, 24f, 32f, FontWeight.W600).scaled(scale).copy(fontFeatureSettings = "tnum")

    /** A news card headline: Literata 500 16 / 22 (a reading text, as the feed wrote it). */
    val newsHeadline: TextStyle = style(LiterataFamily, 16f, 22f, FontWeight.W500).scaled(scale)

    internal val all: List<TextStyle>
        get() = listOf(
            wordmark, wordmarkSmall, codeInline, details, fingerprint, pairingCode, fingerprintShort, liveStatus,
            liveStatusSmall, chatTitle, chatText, toolName, toolSummary, toolOutput, quote, quoteGreek, quoteCitation,
            stockCode, stockCodeSmall, stockPrice, stockChange, stockValue, stockValueLarge, newsHeadline,
        )

    /** Times and durations use tabular figures. */
    fun tabular(base: TextStyle): TextStyle = base.copy(fontFeatureSettings = "tnum")
}

internal val LocalAristotleStyles = staticCompositionLocalOf { AristotleStyles(1f) }

/** The [AristotleStyles] at the text size AristotleTheme was given; read like `MaterialTheme.typography`. */
val AristotleTextStyles: AristotleStyles
    @Composable
    @ReadOnlyComposable
    get() = LocalAristotleStyles.current
