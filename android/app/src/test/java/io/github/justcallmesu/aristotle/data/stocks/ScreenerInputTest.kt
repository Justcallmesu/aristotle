package io.github.justcallmesu.aristotle.data.stocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TSC3: the settings page mirrors the server's checks (`screener_settings.go` `valid()`, §20.2 `every_min` 5..30), so
 * Save never sends a body the server answers `400 bad_request`; and the Edit list sheet mirrors the universe PUT.
 */
class ScreenerInputTest {
    private val saved = ScreenerSettings()
    private val draft = ScreenerDraft.of(saved, every = 10)

    @Test
    fun `a fresh draft shows the saved values and offers no Save`() {
        assertEquals("20", draft.text(ScreenerField.BreakoutLookback))
        assertEquals("1,5", draft.text(ScreenerField.BreakoutVolMult))
        assertEquals("2", draft.text(ScreenerField.SpikeMult))
        assertEquals("30", draft.text(ScreenerField.RsiLevel))
        assertEquals("5", draft.text(ScreenerField.MinValueBn))
        assertEquals("10", draft.text(ScreenerField.ScanEvery))
        assertTrue(draft.valid)
        assertFalse(draft.canSave)
        assertEquals(saved, draft.settings())
    }

    /** The server test's bad bodies, as typed values: each is refused here too. */
    @Test
    fun `the server's out-of-range values are errors here`() {
        val bad = listOf(
            ScreenerField.MinValueBn to "100,5",
            ScreenerField.BreakoutLookback to "9",
            ScreenerField.BreakoutLookback to "20,5",
            ScreenerField.BreakoutVolMult to "0,9",
            ScreenerField.SpikeMult to "1,4",
            ScreenerField.RsiLevel to "46",
            ScreenerField.GapMinPct to "0,4",
            ScreenerField.ScanEvery to "4",
            ScreenerField.ScanEvery to "31",
        )
        bad.forEach { (field, text) ->
            val d = draft.withText(field, text)
            assertTrue("$field $text", d.problem(field) != null)
            assertFalse("$field $text", d.canSave)
            assertNull("$field $text", d.settings())
        }
        assertEquals(FieldProblem.NotWhole, draft.withText(ScreenerField.BreakoutLookback, "20,5").problem(ScreenerField.BreakoutLookback))
        assertEquals(FieldProblem.NotWhole, draft.withText(ScreenerField.ScanEvery, "7.5").problem(ScreenerField.ScanEvery))
        assertEquals(FieldProblem.TooManyDecimals, draft.withText(ScreenerField.GapMinPct, "2,125").problem(ScreenerField.GapMinPct))
        assertEquals(FieldProblem.Empty, draft.withText(ScreenerField.RsiLevel, "").problem(ScreenerField.RsiLevel))
        assertEquals(FieldProblem.NotANumber, draft.withText(ScreenerField.RsiLevel, "3a").problem(ScreenerField.RsiLevel))
        assertEquals(FieldProblem.NotANumber, draft.withText(ScreenerField.RsiLevel, "-3").problem(ScreenerField.RsiLevel))
    }

    /** The server test's "good" body: every value at a range end, as typed on the phone. */
    @Test
    fun `the range ends are accepted and become the server's good body`() {
        val d = draft
            .withText(ScreenerField.MinValueBn, "0").withOn(SetupKey.Breakout, false).withText(ScreenerField.BreakoutLookback, "60")
            .withText(ScreenerField.BreakoutVolMult, "5").withText(ScreenerField.SpikeMult, "1.5").withOn(SetupKey.MaCross, false)
            .withText(ScreenerField.RsiLevel, "45").withText(ScreenerField.GapMinPct, "0,5").withGap(up = true, down = false)
        assertTrue(d.valid)
        assertTrue(d.canSave)
        assertEquals(StocksParser.screenerSettings(ScreenerFixtures.SETTINGS_GOOD), d.settings())
        assertTrue(d.settingsChanged)
        assertFalse(d.everyChanged)
    }

    @Test
    fun `a wrong value under a setup that is off is not an error and the saved value goes out`() {
        val d = draft.withText(ScreenerField.BreakoutLookback, "75").withOn(SetupKey.Breakout, false)
        assertNull(d.problem(ScreenerField.BreakoutLookback))
        assertTrue(d.canSave)
        val out = d.settings()!!
        assertFalse(out.setups.breakout.on)
        assertEquals(20, out.setups.breakout.lookback)
        // Switched back on, the error shows again.
        assertTrue(d.withOn(SetupKey.Breakout, true).problem(ScreenerField.BreakoutLookback) != null)
    }

    @Test
    fun `only the interval changed writes the job, not the settings`() {
        val d = draft.withText(ScreenerField.ScanEvery, "15")
        assertTrue(d.canSave)
        assertFalse(d.settingsChanged)
        assertTrue(d.everyChanged)
        assertEquals(15, d.every())
        // The job unknown: no interval field, nothing to write.
        val noJob = ScreenerDraft.of(saved, every = null)
        assertFalse(noJob.visible(ScreenerField.ScanEvery))
        assertNull(noJob.every())
        assertFalse(noJob.withText(ScreenerField.ScanEvery, "99").canSave)
    }

    @Test
    fun `minus and plus step on the field's grid and stop at the ends`() {
        assertEquals("2", draft.stepped(ScreenerField.BreakoutVolMult, 1).text(ScreenerField.BreakoutVolMult))
        assertEquals("1", draft.stepped(ScreenerField.BreakoutVolMult, -1).text(ScreenerField.BreakoutVolMult))
        val odd = draft.withText(ScreenerField.BreakoutVolMult, "1,7")
        assertEquals("2", odd.stepped(ScreenerField.BreakoutVolMult, 1).text(ScreenerField.BreakoutVolMult))
        assertEquals("1,5", odd.stepped(ScreenerField.BreakoutVolMult, -1).text(ScreenerField.BreakoutVolMult))
        assertEquals("15", draft.stepped(ScreenerField.ScanEvery, 1).text(ScreenerField.ScanEvery))
        val top = draft.withText(ScreenerField.ScanEvery, "30")
        assertFalse(top.canStep(ScreenerField.ScanEvery, 1))
        assertTrue(top.canStep(ScreenerField.ScanEvery, -1))
        assertEquals("30", top.stepped(ScreenerField.ScanEvery, 1).text(ScreenerField.ScanEvery))
        val bottom = draft.withText(ScreenerField.MinValueBn, "0")
        assertFalse(bottom.canStep(ScreenerField.MinValueBn, -1))
        // Out of range: the step brings it back to the near end.
        val above = draft.withText(ScreenerField.BreakoutLookback, "75")
        assertFalse(above.canStep(ScreenerField.BreakoutLookback, 1))
        assertEquals("60", above.stepped(ScreenerField.BreakoutLookback, -1).text(ScreenerField.BreakoutLookback))
        // Not a number: steps from the saved value.
        assertEquals("21", draft.withText(ScreenerField.BreakoutLookback, "x").stepped(ScreenerField.BreakoutLookback, 1).text(ScreenerField.BreakoutLookback))
    }

    @Test
    fun `Defaults fills the server's starting values against what is saved`() {
        val changed = ScreenerSettings(minValueBn = 10.0, setups = SetupSettings(gap = GapSettings(on = false)))
        val d = ScreenerDraft.defaults(changed, baseEvery = 20)
        assertEquals(changed, d.base)
        assertEquals("5", d.text(ScreenerField.MinValueBn))
        assertEquals("10", d.text(ScreenerField.ScanEvery))
        assertTrue(d.isOn(SetupKey.Gap))
        assertTrue(d.canSave)
        assertEquals(ScreenerSettings(), d.settings())
        assertEquals(10, d.every())
    }

    @Test
    fun `the Edit list sheet checks codes, count and period as the universe PUT does`() {
        val ok = UniverseInput.check("bbca, bbri\nTLKM bbca", " TLKM ASII ", "", "2027-01-29")
        assertTrue(ok.ok)
        assertEquals(listOf("BBCA", "BBRI", "TLKM"), ok.lq45)
        assertEquals(listOf("TLKM", "ASII"), ok.idx80)
        assertEquals(4, ok.count) // distinct over both lists
        assertNull(ok.validFrom)
        assertEquals("2027-01-29", ok.validTo)

        assertEquals(listOf("BBCA.JK", "BC"), UniverseInput.check("BBCA.JK BC TLKM", "", "", "2027-01-29").badCodes)
        assertTrue(UniverseProblem.NoCodes in UniverseInput.check(" ", "", "", "2027-01-29").problems)
        val many = (0 until 201).map { i -> "A" + ('A' + i / 26 / 26 % 26) + ('A' + i / 26 % 26) + ('A' + i % 26) }
        assertEquals(201, many.distinct().size)
        assertTrue(UniverseProblem.TooMany in UniverseInput.check(many.joinToString(" "), "", "", "2027-01-29").problems)
        assertTrue(UniverseInput.check(many.take(200).joinToString(" "), "", "", "2027-01-29").ok)
        assertTrue(UniverseProblem.BadValidTo in UniverseInput.check("BBCA", "", "", "29/01/2027").problems)
        assertTrue(UniverseProblem.BadValidTo in UniverseInput.check("BBCA", "", "", "2027-02-30").problems)
        assertTrue(UniverseProblem.BadValidFrom in UniverseInput.check("BBCA", "", "2026-11", "2027-01-29").problems)
        assertTrue(UniverseProblem.FromAfterTo in UniverseInput.check("BBCA", "", "2027-02-01", "2027-01-29").problems)
        assertEquals("2026-11-02", UniverseInput.check("BBCA", "", "2026-11-02", "2027-01-29").validFrom)
        assertEquals("BBCA TLKM", UniverseInput.text(listOf("BBCA", "TLKM")))
    }
}
