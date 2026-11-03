package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import io.github.justcallmesu.aristotle.R

// docs/design.md §2: downloadable Google Fonts, each family ending in a device fallback so a phone
// without Play services still renders.

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val weights = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600)

private fun downloadableFamily(name: String, deviceFallback: String): FontFamily {
    val font = GoogleFont(name)
    val downloadable = weights.map { Font(googleFont = font, fontProvider = fontProvider, weight = it) }
    val fallback = weights.map { Font(familyName = DeviceFontFamilyName(deviceFallback), weight = it) }
    return FontFamily(downloadable + fallback)
}

val LiterataFamily: FontFamily = downloadableFamily("Literata", "serif")
val AtkinsonFamily: FontFamily = downloadableFamily("Atkinson Hyperlegible Next", "sans-serif")
val AtkinsonMonoFamily: FontFamily = downloadableFamily("Atkinson Hyperlegible Mono", "monospace")

private fun style(family: FontFamily, size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
    )

internal val AristotleTypography = Typography(
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

/** Styles outside the M3 slots (design.md §2 table). Screens use these, never ad-hoc sizes. */
object AristotleTextStyles {
    val wordmark: TextStyle = style(LiterataFamily, 22f, 28f, FontWeight.W600, 0.2f)
    val wordmarkSmall: TextStyle = style(LiterataFamily, 18f, 24f, FontWeight.W600, 0.2f)
    val codeInline: TextStyle = style(AtkinsonMonoFamily, 13f, 18f, FontWeight.W400)
    val details: TextStyle = style(AtkinsonMonoFamily, 12f, 18f, FontWeight.W400)
    val fingerprint: TextStyle = style(AtkinsonMonoFamily, 17f, 24f, FontWeight.W500, 0.5f)
    val pairingCode: TextStyle = style(AtkinsonMonoFamily, 22f, 28f, FontWeight.W500, 1f)
    val fingerprintShort: TextStyle = style(AtkinsonMonoFamily, 12f, 16f, FontWeight.W400)

    /** Times and durations use tabular figures. */
    fun tabular(base: TextStyle): TextStyle = base.copy(fontFeatureSettings = "tnum")
}
