package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.stocks.BriefBody
import io.github.justcallmesu.aristotle.data.stocks.BriefSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBA2b MAJOR-1: the brief page's day chips are chosen by date against the latest brief, not by position. */
class BriefDayChipsTest {
    private fun row(date: String) = BriefSummary(date = date, state = "done")

    private fun state(latest: BriefBody?, kept: List<String>, picked: String? = null) =
        BriefUiState(latest = latest, list = kept.map(::row), picked = picked)

    private val pendingToday = BriefBody(date = "2026-11-05", state = BriefBody.PENDING)

    @Test
    fun `before the brief runs, the chips are today (the latest) and yesterday - both reachable, also after a pick`() {
        val s = state(pendingToday, listOf("2026-11-04"))
        assertEquals(
            listOf(DayChip("2026-11-05", selected = true, pick = null), DayChip("2026-11-04", selected = false, pick = "2026-11-04")),
            s.dayChips(),
        )
        val picked = s.copy(picked = "2026-11-04")
        assertEquals(
            listOf(DayChip("2026-11-05", selected = false, pick = null), DayChip("2026-11-04", selected = true, pick = "2026-11-04")),
            picked.dayChips(),
        )
    }

    @Test
    fun `a single kept brief that is the latest has no chips`() {
        assertTrue(state(BriefBody(date = "2026-11-04", state = "done"), listOf("2026-11-04")).dayChips().isEmpty())
        assertTrue(state(null, emptyList()).dayChips().isEmpty())
        assertTrue(BriefUiState(latest = pendingToday, list = null).dayChips().isEmpty())
        assertTrue("a pending today and nothing kept: nothing else to open", state(pendingToday, emptyList()).dayChips().isEmpty())
    }

    @Test
    fun `the normal 7-day list - the latest's chip is selected and picks the latest again`() {
        val days = listOf("2026-11-05", "2026-11-04", "2026-11-03", "2026-11-02", "2026-11-01", "2026-10-31", "2026-10-30")
        val latest = BriefBody(date = "2026-11-05", state = "done")
        val chips = state(latest, days).dayChips()
        assertEquals(days, chips.map { it.date })
        assertEquals(listOf("2026-11-05"), chips.filter { it.selected }.map { it.date })
        assertEquals(null, chips[0].pick)
        assertEquals("2026-11-03", chips[2].pick)
        val older = state(latest, days, picked = "2026-11-03").dayChips()
        assertEquals(listOf("2026-11-03"), older.filter { it.selected }.map { it.date })
        assertEquals(null, older[0].pick)
    }

    @Test
    fun `no latest read yet but kept briefs listed - they can all be opened`() {
        val chips = state(null, listOf("2026-11-04", "2026-11-03")).dayChips()
        assertEquals(listOf("2026-11-04", "2026-11-03"), chips.map { it.pick })
        assertTrue(chips.none { it.selected })
    }
}
