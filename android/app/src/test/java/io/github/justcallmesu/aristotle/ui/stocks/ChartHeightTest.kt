package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.justcallmesu.aristotle.data.stocks.ChartFit
import io.github.justcallmesu.aristotle.ui.theme.AristotleStyles
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import io.github.justcallmesu.aristotle.ui.theme.aristotleTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.max

/**
 * TM3a2 (review MAJOR-2): the chart box is sized from the measured time-label line, so the labels stay at every text
 * size. The screen measures with Compose's TextMeasurer; that needs Android's text stack, which these JVM tests do not
 * have (no Robolectric), so [measuredLinePx] stands in for it, fed with the real inputs:
 * - the real axis style, `AristotleStyles(appScale).chartAxis` (11 / 14 sp times the in-app text size);
 * - sp → px through a real `Density(density, fontScale)`: on Android its FontScaling uses Compose's own
 *   FontScaleConverterFactory, non-linear from 1.03x (checked below: 14 sp at 1.3x is 18.8 dp, not 18.2);
 * - the font's own height, Atkinson Hyperlegible Next hhea ascent 984 + descent 316 per 1000 em (1.3 em), taking the
 *   larger of it and the line height, rounded up to whole px as `TextLayoutResult.size` is.
 * The sizing itself ([chartHeightFor], [labelTopPx], `Dp.roundToPx` as Modifier.height does) is the screen's code.
 */
class ChartHeightTest {
    private fun measuredLinePx(style: TextStyle, density: Density): Float = with(density) {
        ceil(max(style.lineHeight.toPx(), style.fontSize.toPx() * ATKINSON_EM_HEIGHT))
    }

    @Test
    fun theDensityScalesFontsNonLinearlyAsOnAPhone() {
        with(Density(1f, 1.3f)) {
            assertEquals(18.8f, 14.sp.toDp().value, 1e-3f)
            assertEquals(14.3f, 11.sp.toDp().value, 1e-3f)
        }
    }

    @Test
    fun timeLabelsEndInsideTheBoxAtEveryTextSize() {
        for (appScale in listOf(1f, 1.15f)) {
            val style = AristotleStyles(appScale).chartAxis
            for (fontScale in listOf(1f, 1.3f)) {
                for (d in listOf(1f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
                    val density = Density(d, fontScale)
                    val line = measuredLinePx(style, density)
                    val box = with(density) { chartHeightFor(density, line).roundToPx() }
                    val what = "app $appScale × font $fontScale at ${d}x: line $line px, box $box px"
                    assertTrue(what, ChartFit.fitsBelow(labelTopPx(density), line, box.toFloat()))
                    if (appScale == 1f && fontScale == 1f) assertEquals(what, with(density) { CHART_HEIGHT.roundToPx() }, box)
                }
            }
        }
    }

    /** The two cases the review found: the old 14 × fontScale box dropped every label there. */
    @Test
    fun theOldLinearBoxDroppedTheLabelsWhereTheMeasuredOneKeepsThem() {
        val cases = listOf(1.15f to 1f, 1f to 1.3f, 1.15f to 1.3f)
        cases.forEach { (appScale, fontScale) ->
            val density = Density(2.625f, fontScale)
            val line = measuredLinePx(AristotleStyles(appScale).chartAxis, density)
            val old = with(density) { max(250f, 234f + 14f * fontScale).dp.roundToPx() }
            assertFalse("old box, app $appScale × font $fontScale", ChartFit.fitsBelow(labelTopPx(density), line, old.toFloat()))
            val now = with(density) { chartHeightFor(density, line).roundToPx() }
            assertTrue("new box, app $appScale × font $fontScale", ChartFit.fitsBelow(labelTopPx(density), line, now.toFloat()))
        }
    }

    /** TM4: the readout's two lines (close/span + change and arrow; then O/H/L or high/low) end inside its fixed box. */
    @Test
    fun theReadoutsTwoLinesEndInsideItsBoxAtEveryTextSize() {
        for (appScale in listOf(1f, 1.15f)) {
            val type = aristotleTypography(appScale)
            val change = AristotleStyles(appScale).stockChange
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                for (d in listOf(1f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
                    val density = Density(d, fontScale)
                    val arrow = with(density) { ARROW.roundToPx().toFloat() }
                    val first = maxOf(measuredLinePx(type.labelMedium, density), measuredLinePx(change, density), arrow)
                    val second = measuredLinePx(type.bodySmall, density)
                    val box = with(density) { readoutHeightFor(density, first, second).roundToPx() }
                    val top = with(density) { Spacing.xs.toPx() }
                    val what = "app $appScale × font $fontScale at ${d}x: lines $first + $second px, box $box px"
                    assertTrue(what, top + first + second <= box)
                    if (appScale == 1f && fontScale == 1f) assertEquals(what, with(density) { 44.dp.roundToPx() }, box)
                }
            }
        }
    }

    /** A measured readout taller than the floor gets the measured height plus the slack; a short one keeps the floor. */
    @Test
    fun theReadoutGrowsPastItsFloorOnlyWhenItsLinesNeedIt() {
        val density = Density(1f, 1f)
        assertEquals(44f, readoutHeightFor(density, 18f, 16f).value, 1e-4f)
        assertEquals(4f + 30f + 20f + ChartFit.SLACK_PX, readoutHeightFor(density, 30f, 20f).value, 1e-4f)
        assertEquals(44f * 1.3f, readoutHeightFor(Density(1f, 1.3f), 18f, 16f).value, 1e-3f)
    }

    private companion object {
        const val ATKINSON_EM_HEIGHT = 1.3f
    }
}
