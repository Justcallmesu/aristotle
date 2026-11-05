package io.github.justcallmesu.aristotle.ui.stocks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TM3a2 review MINOR-A: [ChartHeightTest] feeds `chartHeightFor` a stand-in measurer (no Android text stack in JVM tests),
 * so it cannot see a regression in the real measuring path. This guard reads the source instead: `chartHeight()` measures
 * with the same `AristotleTextStyles.chartAxis` the Canvas draws its time labels with, at `LocalDensity.current` (the
 * phone's density and font scale, not a `Density(density.density)` without it), with style and density in the `remember`
 * key, and hands the measured height to `chartHeightFor`; and the chart box uses `chartHeight()`.
 */
class ChartHeightSourceTest {
    private val source: String by lazy {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/stocks/PriceChart.kt"
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    }

    /** The body of `internal fun chartHeight(): Dp { … }`, up to its closing brace at the start of a line. */
    private fun chartHeightBody(): String {
        val start = source.indexOf("internal fun chartHeight(): Dp {")
        assertTrue("chartHeight() is where it was", start >= 0)
        val end = source.indexOf("\n}", start)
        return source.substring(start, end)
    }

    @Test
    fun `chartHeight measures with the Canvas's axis style at the real density`() {
        val body = chartHeightBody()
        assertTrue(body, body.contains("val density = LocalDensity.current"))
        assertTrue(body, body.contains("rememberTextMeasurer()"))
        assertTrue(body, body.contains("val style = AristotleTextStyles.chartAxis"))
        assertTrue(body, Regex("measurer\\.measure\\([^)]*style[^)]*density = density\\)").containsMatchIn(body))
        assertTrue("style and density key the remembered line", Regex("remember\\([^)]*style[^)]*density[^)]*\\)").containsMatchIn(body))
        assertTrue(body, body.contains("chartHeightFor(density, line"))
        assertFalse("no density rebuilt without the font scale", body.contains("Density("))
        assertFalse("no fixed sp guess", Regex("\\d+(\\.\\d+)?f?\\s*\\*\\s*fontScale").containsMatchIn(body))
    }

    @Test
    fun `the Canvas draws its labels with that same style, and the box takes the measured height`() {
        val drawer = source.substringAfter("val measurer = rememberTextMeasurer()").substringBefore("Box(")
        assertTrue(drawer, drawer.contains("val axisStyle = AristotleTextStyles.chartAxis"))
        assertTrue(source.contains(".height(chartHeight())"))
        // One axis style for both: no second chartAxis-like style defined in the file.
        assertEquals(2, Regex("AristotleTextStyles\\.chartAxis\\b").findAll(source).count())
    }
}
