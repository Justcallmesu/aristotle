package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TSC3: setup labels from `values`, the filter, and the state mapping of the board's states (screener-notes §3). */
class ScreenerLogicTest {
    private val intraday = StocksParser.screener(ScreenerFixtures.INTRADAY)!!
    private val eod = StocksParser.screener(ScreenerFixtures.EOD)!!
    private val noBars = StocksParser.screener(ScreenerFixtures.NO_BARS)!!

    @Test
    fun `labels are built from values, strongest first, ARA before all`() {
        val bbca = intraday.candidates.single()
        assertEquals(
            listOf(SetupLabel.Breakout(20), SetupLabel.Volume("2,2"), SetupLabel.Gap(up = true, pct = "+2,4\u00A0%")),
            SetupLabels.of(bbca),
        )
        // The server's order is breakout, volume, gap; a lower-weight setup listed first still sorts after.
        val mixed = bbca.copy(atAra = true, setups = listOf(bbca.setups[2], ScreenerSetup("rsi_rebound", values = SetupValues(rsi = 31.4)), bbca.setups[0]))
        assertEquals(listOf(SetupLabel.Ara, SetupLabel.Breakout(20), SetupLabel.Rsi(31), SetupLabel.Gap(true, "+2,4\u00A0%")), SetupLabels.of(mixed))
    }

    @Test
    fun `bearish labels carry the sign or the arrow, never colour alone`() {
        val gapDown = SetupLabels.of(intraday.flags.single()).single()
        assertEquals(SetupLabel.Gap(up = false, pct = "\u22123,2\u00A0%"), gapDown)
        assertTrue(gapDown.bearish)
        val cross = SetupLabels.of(eod.flags.single()).single()
        assertEquals(SetupLabel.MaCross(up = false, date = LocalDate.of(2026, 11, 5)), cross)
        assertTrue(cross.bearish)
        assertFalse(SetupLabels.of(eod.candidates.single()).last().bearish)
        // Without a value the gap still names its direction.
        assertEquals(SetupLabel.Gap(false, null), SetupLabels.label(ScreenerSetup("gap", dir = "down")))
        // An id this phone does not know is left out, not shown wrong.
        assertEquals(emptyList<SetupLabel>(), SetupLabels.of(intraday.flags.single().copy(setups = listOf(ScreenerSetup("new_setup")))))
    }

    @Test
    fun `at most three labels and the count of the rest`() {
        val labels = listOf(SetupLabel.Ara, SetupLabel.Breakout(20), SetupLabel.Volume("6,1"), SetupLabel.Gap(true, "+4,3\u00A0%"))
        assertEquals(ShownLabels(labels.take(3), 1), SetupLabels.shown(labels, 3))
        assertEquals(ShownLabels(labels.take(1), 3), SetupLabels.shown(labels, 1))
        assertEquals(ShownLabels(emptyList(), 0), SetupLabels.shown(emptyList(), 3))
    }

    @Test
    fun `numbers are written the Indonesian way`() {
        assertEquals("5,2", SetupLabels.score(5.2))
        assertEquals("6,2", SetupLabels.score(6.2))
        assertEquals("2,0", SetupLabels.oneDecimal(2.0))
        assertEquals("2,5", SetupLabels.oneDecimal(2.45)) // half up
        assertEquals("\u22122,9\u00A0%", SetupLabels.signedPercentOneDecimal(-2.94))
        assertEquals("0,0\u00A0%", SetupLabels.signedPercentOneDecimal(-0.04))
    }

    @Test
    fun `a watchlist or holding row never hides a bearish flag, even when the code is also a candidate (Q106)`() {
        val both = StocksParser.screener(ScreenerFixtures.BOTH_LISTS)!!
        val labels = SetupLabels.forCode("BBRI", both)
        // Candidate and flag merged, the bearish label first: the row's one visible slot is the gap down, "+1" the volume.
        assertEquals(listOf(SetupLabel.Gap(up = false, pct = "\u22122,9\u00A0%"), SetupLabel.Volume("2,3")), labels)
        assertEquals(ShownLabels(listOf(SetupLabel.Gap(false, "\u22122,9\u00A0%")), 1), SetupLabels.shown(labels, 1))
        // Only a candidate, or only a flag: that row's labels.
        assertEquals(SetupLabel.Breakout(20), SetupLabels.forCode("BBCA", intraday).first())
        assertTrue(SetupLabels.forCode("ANTM", intraday).single().bearish)
        assertTrue(SetupLabels.forCode("TLKM", intraday).isEmpty())
        assertTrue(SetupLabels.forCode("BBCA", null).isEmpty())
        // ARA stays ahead of the bullish labels, behind a bearish one.
        val ara = both.copy(candidates = both.candidates.map { it.copy(atAra = true) })
        assertEquals(listOf(SetupLabel.Gap(false, "\u22122,9\u00A0%"), SetupLabel.Ara, SetupLabel.Volume("2,3")), SetupLabels.forCode("BBRI", ara))
    }

    @Test
    fun `the filter keeps rows with that setup`() {
        val rows = intraday.candidates + intraday.flags
        assertEquals(listOf("BBCA", "ANTM"), ScreenerStates.filtered(rows, SetupFilter.All).map { it.code })
        assertEquals(listOf("BBCA", "ANTM"), ScreenerStates.filtered(rows, SetupFilter.Gap).map { it.code })
        assertEquals(listOf("BBCA"), ScreenerStates.filtered(rows, SetupFilter.Volume).map { it.code })
        assertTrue(ScreenerStates.filtered(rows, SetupFilter.Rsi).isEmpty())
        assertEquals(listOf("ANTM"), ScreenerStates.filtered(eod.flags, SetupFilter.Ma).map { it.code })
    }

    @Test
    fun `nothing read yet is loading while the PC answers, not loaded while it does not`() {
        assertEquals(ScreenerKind.Loading, ScreenerStates.of(null, reachable = true).kind)
        assertEquals(ScreenerKind.NotLoaded, ScreenerStates.of(null, reachable = false).kind)
    }

    @Test
    fun `the board's states map from the contract`() {
        // Open: a list.
        val open = ScreenerStates.of(intraday, reachable = true)
        assertEquals(ScreenerKind.List, open.kind)
        assertFalse(open.muted)
        // Before the first bars run.
        assertEquals(ScreenerKind.NoBars, ScreenerStates.of(noBars, true).kind)
        // An intraday view with no result: no scan yet today; with no_bars it is still the building state.
        val noScan = intraday.copy(sessionDate = null, dataAsOf = null, candidates = emptyList(), flags = emptyList(), scan = ScreenerScan())
        assertEquals(ScreenerKind.NoScanYet, ScreenerStates.of(noScan, true).kind)
        assertEquals(ScreenerKind.NoBars, ScreenerStates.of(noScan.copy(scan = ScreenerScan(error = "no_bars")), true).kind)
        // No candidates (flags may stay).
        assertEquals(ScreenerKind.Empty, ScreenerStates.of(intraday.copy(candidates = emptyList()), true).kind)
        // Yahoo blocked: the last list, stale, muted.
        val blocked = ScreenerStates.of(intraday.copy(stale = true, scan = intraday.scan.copy(status = "failed", error = "upstream_blocked")), true)
        assertEquals(ScreenerKind.List, blocked.kind)
        assertTrue(blocked.stale && blocked.blocked && blocked.muted)
        // Stale for another reason is not "Yahoo refused".
        assertFalse(ScreenerStates.of(intraday.copy(stale = true, scan = intraday.scan.copy(status = "failed", error = "codes_failed")), true).blocked)
        // PC offline: the list kept here, muted.
        val offline = ScreenerStates.of(intraday, reachable = false)
        assertEquals(ScreenerKind.List, offline.kind)
        assertTrue(offline.offline && offline.muted)
        // Market closed: the last close's list.
        assertEquals(ScreenerKind.List, ScreenerStates.of(eod, true).kind)
        // Partial scan and the session break.
        assertEquals(3, ScreenerStates.of(intraday.copy(scan = intraday.scan.copy(status = "partial", codesFailed = 3)), true).failedCodes)
        assertEquals(0, ScreenerStates.of(intraday.copy(scan = intraday.scan.copy(status = "ok", codesFailed = 3)), true).failedCodes)
        assertTrue(ScreenerStates.of(intraday.copy(market = MarketStatus(state = "break")), true).sessionBreak)
    }
}
