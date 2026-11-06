package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TR2b MINOR-1: "N verdicts scored · through <date>" moved from the one-line top bar (269 dp left at 1.3x) into the page body,
 * where it may wrap. Compose's TextMeasurer needs Android's text stack, which JVM tests do not have, so the width comes from the
 * real Atkinson Next file through AWT at the phone's non-linear 1.3x font scale (as [ChartHeightTest] does for the chart), for
 * every date of a year in English and Indonesian, and the source is read to prove the line is not clipped.
 */
class TrackSublineFitTest {
    private fun file(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    private val font: Font by lazy { Font.createFont(Font.TRUETYPE_FONT, file("res/font/atkinson_next_regular.ttf")) }

    private fun widthDp(text: String, sp: Float, fontScale: Float): Float {
        val dp = with(Density(1f, fontScale)) { sp.sp.toDp().value }
        return font.deriveFont(dp).getStringBounds(text, FontRenderContext(null, true, true)).width.toFloat()
    }

    private fun pluralOther(strings: String): String =
        Regex("<plurals name=\"stocks_record_subline\">.*?<item quantity=\"other\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL).find(strings)!!.groupValues[1]
            .replace("%1\$d", "%1\$s")

    @Test
    fun `the subline fits one line of the body at 393 dp and 1_3x font, and two lines at the largest in-app size, in both languages`() {
        val body = 393f - 2 * 16f
        for ((res, locale) in listOf("values" to Locale.ENGLISH, "values-in" to Locale.forLanguageTag("id"))) {
            val template = pluralOther(file("res/$res/strings.xml").readText())
            for (appScale in listOf(1f, 1.15f)) {
                for (offset in 0L until 365L) {
                    val day = StockTimes.day(LocalDate.of(2026, 1, 1).plusDays(offset), locale)
                    val text = template.format("9999", day)
                    val width = widthDp(text, 12f * appScale, 1.3f) * 1.05f
                    val lines = if (appScale == 1f) 1 else 2
                    assertTrue("$res app $appScale: \"$text\" is $width dp of $lines x $body", width <= lines * body)
                }
            }
        }
    }

    @Test
    fun `the old top bar slot was too narrow for it, which is why it moved`() {
        val template = pluralOther(file("res/values/strings.xml").readText())
        assertTrue(widthDp(template.format("9", "Wed 30 Sep"), 12f, 1.3f) > 268f)
    }

    @Test
    fun `the page draws the subline in the body without maxLines and not in the top bar`() {
        val src = file("java/io/github/justcallmesu/aristotle/ui/stocks/TrackRecordScreen.kt").readText()
        val bar = src.substringAfter("StocksTopBar(").substringBefore("LazyColumn(")
        assertTrue(bar, bar.contains("subline = null"))
        assertFalse(bar, bar.contains("trackSubline"))
        val line = src.substringAfter("private fun TrackSubline(").substringBefore("\n}")
        assertTrue(line, line.contains("Text("))
        assertFalse("a clipped line is the bug", line.contains("maxLines") || line.contains("TextOverflow"))
        assertEquals(1, Regex("TrackSubline\\(").findAll(src.substringBefore("private fun TrackSubline(")).count())
    }
}
