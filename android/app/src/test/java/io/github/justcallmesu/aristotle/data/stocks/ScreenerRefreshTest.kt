package io.github.justcallmesu.aristotle.data.stocks

import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TSC3: when the screener is read again (scan windows, 60 s, the 3 s follow-up) and Scan now's 2 min cooldown. */
class ScreenerRefreshTest {
    private fun at(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `the scan windows are the server's, by the WIB wall clock`() {
        // Thu 5 Nov 2026 (Mon-Thu windows), as the server's TestScreenerFileUnusable table.
        listOf(
            "2026-11-05T09:14:00+07:00" to false, "2026-11-05T09:15:00+07:00" to true, "2026-11-05T12:14:59+07:00" to true,
            "2026-11-05T12:15:00+07:00" to false, "2026-11-05T12:20:00+07:00" to false, "2026-11-05T13:40:00+07:00" to true,
            "2026-11-05T16:14:00+07:00" to true, "2026-11-05T16:15:00+07:00" to false, "2026-11-07T10:00:00+07:00" to false,
            // Friday: [09:15, 11:45) and [14:10, 16:15).
            "2026-11-06T11:44:00+07:00" to true, "2026-11-06T11:45:00+07:00" to false, "2026-11-06T14:00:00+07:00" to false,
            "2026-11-06T14:10:00+07:00" to true,
            // The phone's own zone does not matter: 02:20 UTC is 09:20 WIB.
            "2026-11-05T02:20:00Z" to true,
        ).forEach { (time, inside) -> assertEquals(time, inside, ScreenerRefresh.inScanWindow(at(time))) }
    }

    @Test
    fun `the next window opens after the break, after the close, and after the weekend`() {
        assertEquals(at("2026-11-05T13:40:00+07:00"), ScreenerRefresh.nextWindowStart(at("2026-11-05T12:30:00+07:00")))
        assertEquals(at("2026-11-06T09:15:00+07:00"), ScreenerRefresh.nextWindowStart(at("2026-11-05T16:30:00+07:00")))
        assertEquals(at("2026-11-06T14:10:00+07:00"), ScreenerRefresh.nextWindowStart(at("2026-11-06T12:00:00+07:00")))
        assertEquals(at("2026-11-09T09:15:00+07:00"), ScreenerRefresh.nextWindowStart(at("2026-11-06T17:00:00+07:00")))
        // Strictly after now: at 09:15 sharp the next start is 13:40.
        assertEquals(at("2026-11-05T13:40:00+07:00"), ScreenerRefresh.nextWindowStart(at("2026-11-05T09:15:00+07:00")))
    }

    @Test
    fun `nothing is read while hidden or unreachable, at once when never read`() {
        val now = at("2026-11-05T10:00:00+07:00")
        assertNull(ScreenerRefresh.nextDelay(active = false, following = false, closedToday = false, lastReadAt = now, now = now))
        assertEquals(0L, ScreenerRefresh.nextDelay(true, false, false, null, now))
    }

    @Test
    fun `every 60 s inside a window on a trading day`() {
        val now = at("2026-11-05T10:00:00+07:00")
        assertEquals(60_000L, ScreenerRefresh.nextDelay(true, false, false, now, now))
        assertEquals(20_000L, ScreenerRefresh.nextDelay(true, false, false, now - 40_000, now))
        assertEquals(0L, ScreenerRefresh.nextDelay(true, false, false, now - 90_000, now))
        // 16:05: the market has closed, the window has not (the 16:00 and 16:10 scans).
        val late = at("2026-11-05T16:05:00+07:00")
        assertEquals(60_000L, ScreenerRefresh.nextDelay(true, false, false, late, late))
    }

    @Test
    fun `outside a window, or on a holiday, the next read is when the next window opens`() {
        val lunch = at("2026-11-05T12:30:00+07:00")
        assertEquals(at("2026-11-05T13:40:00+07:00") - lunch, ScreenerRefresh.nextDelay(true, false, false, lunch, lunch))
        val evening = at("2026-11-05T19:05:00+07:00")
        assertEquals(at("2026-11-06T09:15:00+07:00") - evening, ScreenerRefresh.nextDelay(true, false, false, evening, evening))
        // A holiday inside the wall-clock window: no 60 s polling.
        val holiday = at("2026-12-24T10:00:00+07:00")
        assertEquals(at("2026-12-24T13:40:00+07:00") - holiday, ScreenerRefresh.nextDelay(true, false, true, holiday, holiday))
        // Right at a window's start, never sooner than a minute after the last read.
        val start = at("2026-11-05T13:39:50+07:00")
        assertEquals(60_000L, ScreenerRefresh.nextDelay(true, false, false, start, start))
    }

    @Test
    fun `a holiday is told from the server's answers, 16_00 to 16_15 of a trading day is not one`() {
        val intraday = StocksParser.screener(ScreenerFixtures.INTRADAY)!!
        val closedMarket = MarketStatus(state = "closed", nextOpen = "2026-11-06T09:00:00+07:00")
        val now = at("2026-11-05T16:05:00+07:00")
        // Thu 16:05: closed, but today's intraday result says it traded.
        val afterClose = intraday.copy(at = "2026-11-05T16:05:00+07:00", market = closedMarket)
        assertFalse(ScreenerRefresh.closedToday(listOf(afterClose), now))
        // Not even a result yet, but a scan ran today (a failed one) or today's next slot is still to come.
        val noResult = afterClose.copy(sessionDate = "2026-11-04", scan = ScreenerScan(lastRun = "2026-11-05T16:00:05+07:00"))
        assertFalse(ScreenerRefresh.closedToday(listOf(noResult), now))
        assertFalse(ScreenerRefresh.closedToday(listOf(noResult.copy(scan = ScreenerScan(nextRun = "2026-11-05T16:10:00+07:00"))), now))
        // Before the open of a trading day: it opens later today.
        val early = at("2026-11-05T08:30:00+07:00")
        assertFalse(ScreenerRefresh.closedToday(listOf(intraday.copy(at = "2026-11-05T08:30:00+07:00", market = MarketStatus("closed", nextOpen = "2026-11-05T09:00:00+07:00"))), early))
        // Christmas Eve 10:00: closed, opens another day, the kept scan is from an earlier day: a holiday.
        val holidayNow = at("2026-12-24T10:00:00+07:00")
        val holiday = noResult.copy(at = "2026-12-24T10:00:00+07:00", market = MarketStatus("closed", nextOpen = "2026-12-28T09:00:00+07:00"),
            scan = ScreenerScan(lastRun = "2026-12-23T16:10:05+07:00", nextRun = "2026-12-28T09:15:00+07:00"))
        assertTrue(ScreenerRefresh.closedToday(listOf(holiday), holidayNow))
        // An eod answer of a holiday says it too; nothing read today says nothing (read, and learn).
        assertTrue(ScreenerRefresh.closedToday(listOf(StocksParser.screener(ScreenerFixtures.EOD)!!.copy(at = "2026-12-24T10:00:00+07:00", market = holiday.market)), holidayNow))
        assertFalse(ScreenerRefresh.closedToday(listOf(afterClose), holidayNow))
        assertFalse(ScreenerRefresh.closedToday(emptyList(), holidayNow))
    }

    @Test
    fun `a scan started here is followed every 3 s, in or out of a window`() {
        val evening = at("2026-11-05T19:05:00+07:00")
        assertEquals(3_000L, ScreenerRefresh.nextDelay(true, true, true, evening, evening))
        assertEquals(1_000L, ScreenerRefresh.nextDelay(true, true, false, evening - 2_000, evening))
        assertNull(ScreenerRefresh.nextDelay(false, true, false, evening, evening))
    }

    @Test
    fun `the cooldown runs 2 min from this phone's own 202`() {
        assertEquals(1_120_000L, ScanCooldown.untilFromStart(1_000_000))
        assertEquals(120, ScanCooldown.secondsLeft(ScanCooldown.untilFromStart(1_000_000), 1_000_000))
    }

    @Test
    fun `a too_soon refusal waits its retry_after_s, else 2 min`() {
        assertEquals(1_120_000L, ScanCooldown.untilFromRefusal(120, 1_000_000))
        assertEquals(1_030_000L, ScanCooldown.untilFromRefusal(30, 1_000_000))
        assertEquals(1_120_000L, ScanCooldown.untilFromRefusal(null, 1_000_000))
        assertEquals(80, ScanCooldown.secondsLeft(1_080_000, 1_000_000))
        assertEquals(1, ScanCooldown.secondsLeft(1_000_001, 1_000_000)) // rounded up: never "in 0:00" while still refused
        assertNull(ScanCooldown.secondsLeft(1_000_000, 1_000_000))
        assertEquals("1:20", ScanCooldown.clock(80))
        assertEquals("0:05", ScanCooldown.clock(5))
        assertEquals(5L, ScanCooldown.later(3L, 5L))
        assertEquals(3L, ScanCooldown.later(3L, null))
    }

    @Test
    fun `the loop reads on every show, re-plans on a poke, and stops when hidden`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val active = MutableStateFlow(false)
        var reads = 0
        val delays = mutableListOf<Long?>()
        var next: Long? = null
        val loop = ScreenerRefresher(scope, active, nextDelay = { _, _ -> next.also { delays += it } }, read = { reads++ }, clock = { 0L }, pause = { awaitCancellation() })
        loop.start()
        assertEquals(0, reads)
        active.value = true
        assertEquals(1, reads)
        active.value = false
        active.value = true
        assertEquals(2, reads)
        // A poke while shown does not read by itself; it asks the delay again.
        val asked = delays.size
        loop.poke()
        assertEquals(2, reads)
        assertTrue(delays.size > asked)
        // A poke while hidden does nothing.
        active.value = false
        loop.poke()
        assertEquals(2, reads)
        assertFalse(delays.size > asked + 1)
        next = 0
        scope.cancel()
    }
}
