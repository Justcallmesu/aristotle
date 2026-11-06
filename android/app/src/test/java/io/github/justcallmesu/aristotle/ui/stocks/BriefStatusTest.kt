package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.stocks.BriefBody
import io.github.justcallmesu.aristotle.data.stocks.BriefCard
import io.github.justcallmesu.aristotle.data.stocks.BriefResult
import io.github.justcallmesu.aristotle.data.stocks.JobView
import io.github.justcallmesu.aristotle.data.stocks.JobsWithRuns
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TBA2c MINOR: the Home card and the brief page read one status function, on the job's own time, with a ticking clock. */
class BriefStatusTest {
    private val today = LocalDate.parse("2026-11-05")
    private val saturday = LocalDate.parse("2026-11-07")
    private val pending = BriefBody(date = "2026-11-05", state = BriefBody.PENDING)
    private val done = BriefBody(date = "2026-11-05", state = BriefBody.DONE, result = BriefResult(headline = "Banks"))

    private fun jobs(on: Boolean = true, at: String? = "08:15") = JobsWithRuns(jobs = listOf(JobView(id = JobsWithRuns.BRIEF, on = on, at = at)))

    private fun state(latest: BriefBody?, jobs: JobsWithRuns? = jobs(), picked: String? = null, shown: BriefBody? = null, missing: Boolean = false) =
        BriefUiState(latest = latest, latestMissing = missing, jobs = jobs, picked = picked, shown = shown ?: latest)

    /** The page and the card must say the same about the latest brief at every moment. */
    private fun both(s: BriefUiState, day: LocalDate, now: LocalTime): BriefCard? {
        assertEquals(s.card(day, now), s.pageStatus(day, now))
        return s.pageStatus(day, now)
    }

    @Test
    fun `pending reads the job's time before the slot and no brief today from 09-00 - page and card alike`() {
        assertEquals(BriefCard.Pending("08:15"), both(state(pending), today, LocalTime.of(8, 59)))
        assertEquals(BriefCard.NoneToday, both(state(pending), today, LocalTime.of(9, 0)))
        assertEquals(BriefCard.NoneToday, both(state(pending), today, LocalTime.of(14, 0)))
    }

    @Test
    fun `running, failed, missed, ready, not a trading day, job off and unknown - the same on both`() {
        val noon = LocalTime.of(12, 0)
        assertEquals(BriefCard.Running, both(state(pending.copy(state = BriefBody.RUNNING)), today, noon))
        assertEquals(BriefCard.Failed("busy"), both(state(pending.copy(state = BriefBody.FAILED, error = "busy")), today, noon))
        assertEquals(BriefCard.Missed, both(state(pending.copy(state = BriefBody.MISSED)), today, noon))
        assertEquals(BriefCard.Ready("Banks", late = true, running = false), both(state(done.copy(late = true)), today, noon))
        val friday = done.copy(date = "2026-11-06")
        assertEquals(BriefCard.NotTradingDay(LocalDate.parse("2026-11-06")), both(state(friday), saturday, noon))
        assertEquals(BriefCard.Off(LocalDate.parse("2026-11-06")), both(state(friday, jobs(on = false)), saturday, noon))
        assertEquals(BriefCard.Unknown, both(state(friday, jobs = null), saturday, noon))
    }

    @Test
    fun `a picked day says what its own brief says, never the pending or no-brief words`() {
        val old = done.copy(date = "2026-11-04", late = true)
        val s = state(pending, picked = "2026-11-04", shown = old)
        assertEquals(BriefCard.Ready("Banks", late = true, running = false), s.pageStatus(today, LocalTime.of(10, 0)))
        assertNull(state(pending, picked = "2026-11-04", shown = old.copy(result = null, state = BriefBody.PENDING)).pageStatus(today, LocalTime.of(8, 0)))
        assertNull("the picked day is not loaded yet", state(pending, picked = "2026-11-04", shown = null).copy(shown = null).pageStatus(today, LocalTime.of(8, 0)))
    }

    @Test
    fun `the clock ticks on the minute so 09-00 shows on an idle screen`() {
        assertEquals(60_000L, MinuteTicks.untilNextMinute(0))
        assertEquals(1_000L, MinuteTicks.untilNextMinute(59_000))
        assertEquals(30_500L, MinuteTicks.untilNextMinute(1_020_000 + 29_500))
    }
}
