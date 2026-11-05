package io.github.justcallmesu.aristotle.data.stocks

import org.junit.Assert.assertEquals
import org.junit.Test

/** TS2 money and number formatting: Indonesian grouping in both languages, U+2212 minus, no-break spaces. */
class StockFormatTest {
    private val m = StockFormat.MINUS
    private val nb = '\u00A0'

    @Test
    fun wholeNumbersGroupWithPoints() {
        assertEquals("0", StockFormat.number(0))
        assertEquals("68", StockFormat.number(68))
        assertEquals("9.875", StockFormat.number(9875))
        assertEquals("1.500", StockFormat.number(1500))
        assertEquals("269.099.800", StockFormat.number(269_099_800))
        assertEquals("${m}2.400", StockFormat.number(-2400))
    }

    @Test
    fun rupiahHasNoDecimals() {
        assertEquals("Rp${nb}4.917.675", StockFormat.rupiah(4_917_675))
        assertEquals("Rp${nb}0", StockFormat.rupiah(0))
        assertEquals("${m}Rp${nb}2.400", StockFormat.rupiah(-2400))
    }

    @Test
    fun changesCarryTheirSign() {
        assertEquals("+Rp${nb}110.475", StockFormat.signedRupiah(110_475))
        assertEquals("${m}Rp${nb}2.400", StockFormat.signedRupiah(-2400))
        assertEquals("Rp${nb}0", StockFormat.signedRupiah(0))
        assertEquals("+90", StockFormat.signedNumber(90))
        assertEquals("${m}1.100", StockFormat.signedNumber(-1100))
        assertEquals("0", StockFormat.signedNumber(0))
    }

    @Test
    fun percentHasACommaTwoDecimalsAndASign() {
        assertEquals("+1,25$nb%", StockFormat.percent(1.25))
        assertEquals("+1,86$nb%", StockFormat.percent(1.86))
        assertEquals("${m}3,23$nb%", StockFormat.percent(-3.23))
        assertEquals("+2,30$nb%", StockFormat.percent(2.3))
        assertEquals("0,00$nb%", StockFormat.percent(0.0))
        // The sign follows the rounded value: −0.004 shows as no change, not "−0,00 %".
        assertEquals("0,00$nb%", StockFormat.percent(-0.004))
        assertEquals("+1.234,57$nb%", StockFormat.percent(1234.567))
    }

    @Test
    fun feesAndAveragePrices() {
        assertEquals("0,15$nb%", StockFormat.fee(0.15))
        assertEquals("0,20$nb%", StockFormat.fee(0.2))
        assertEquals("5,00$nb%", StockFormat.fee(5.0))
        assertEquals("4.800", StockFormat.price(4800.0))
        assertEquals("4.812,51", StockFormat.price(4812.506))
        assertEquals("4.812,5", StockFormat.price(4812.5))
    }

    @Test
    fun missingValuesAreADash() {
        assertEquals("\u2014", StockFormat.orDash(null, StockFormat::rupiah))
        assertEquals("Rp${nb}1", StockFormat.orDash(1, StockFormat::rupiah))
    }
}

/** The P/L display mapping (Q91): every change has a direction (arrow + colour) and a signed text. */
class StockChangesTest {
    private val m = StockFormat.MINUS
    private val nb = '\u00A0'

    private fun quote(change: Long?, pct: Double?) = StockQuote(code = "BBCA", price = 4930, prevClose = 4840, change = change, changePct = pct)

    @Test
    fun quoteChange() {
        assertEquals(ChangeDisplay(Direction.Up, "+90 · +1,86$nb%"), StockChanges.quote(quote(90, 1.86)))
        assertEquals(ChangeDisplay(Direction.Down, "${m}100 · ${m}3,23$nb%"), StockChanges.quote(quote(-100, -3.23)))
        assertEquals(ChangeDisplay(Direction.Flat, "0 · 0,00$nb%"), StockChanges.quote(quote(0, 0.0)))
        // No previous close: no change to show.
        assertEquals(ChangeDisplay(Direction.Unknown, "\u2014"), StockChanges.quote(quote(null, null)))
    }

    @Test
    fun percentOnlyForChips() {
        assertEquals(ChangeDisplay(Direction.Down, "${m}3,23$nb%"), StockChanges.quotePercent(quote(-100, -3.23)))
        assertEquals(Direction.Unknown, StockChanges.quotePercent(null).direction)
    }

    @Test
    fun profitAfterFeesAndToday() {
        assertEquals(ChangeDisplay(Direction.Up, "+Rp${nb}110.475 · +2,30$nb%"), StockChanges.profit(110_475, 2.3))
        assertEquals(ChangeDisplay(Direction.Down, "${m}Rp${nb}2.400 · ${m}0,40$nb%"), StockChanges.profit(-2400, -0.4))
        assertEquals(ChangeDisplay(Direction.Flat, "Rp${nb}0 · 0,00$nb%"), StockChanges.profit(0, 0.0))
        assertEquals(Direction.Unknown, StockChanges.profit(null, null).direction)
        assertEquals(ChangeDisplay(Direction.Up, "+Rp${nb}90.000"), StockChanges.today(90_000))
        assertEquals(ChangeDisplay(Direction.Down, "${m}Rp${nb}20.000"), StockChanges.today(-20_000))
        assertEquals(Direction.Unknown, StockChanges.today(null).direction)
    }

    @Test
    fun directionOfNumbers() {
        assertEquals(Direction.Up, Direction.of(0.01))
        assertEquals(Direction.Flat, Direction.of(0.0))
        assertEquals(Direction.Unknown, Direction.of(Double.NaN))
        assertEquals(Direction.Down, Direction.of(-1L))
    }
}
