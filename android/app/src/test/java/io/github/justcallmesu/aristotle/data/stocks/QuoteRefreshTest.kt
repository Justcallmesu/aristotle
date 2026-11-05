package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS2 refresh rule: quotes every 60 s only while visible, the market open and the link online (fake clock, market state). */
class QuoteRefreshTest {
    private val open = MarketStatus("open", 1, "2026-11-05T12:00:00+07:00", null)
    private val lunch = MarketStatus("break", null, null, "2026-11-05T13:30:00+07:00")
    private val evening = MarketStatus("closed", null, null, "2026-11-06T09:00:00+07:00")
    private fun at(rfc3339: String): Long = OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli()

    @Test
    fun nothingWhileHiddenOrOffline() {
        val now = at("2026-11-05T10:00:00+07:00")
        assertNull(QuoteRefresh.nextDelay(visible = false, online = true, open, now - 70_000, now))
        assertNull(QuoteRefresh.nextDelay(visible = true, online = false, open, now - 70_000, now))
    }

    @Test
    fun everyMinuteWhileTheMarketIsOpen() {
        val now = at("2026-11-05T10:00:00+07:00")
        assertEquals(0L, QuoteRefresh.nextDelay(true, true, open, lastReadAt = null, now = now))
        assertEquals(60_000L, QuoteRefresh.nextDelay(true, true, open, lastReadAt = now, now = now))
        assertEquals(15_000L, QuoteRefresh.nextDelay(true, true, open, lastReadAt = now - 45_000, now = now))
        assertEquals(0L, QuoteRefresh.nextDelay(true, true, open, lastReadAt = now - 90_000, now = now))
    }

    @Test
    fun noPollingOutsideASessionOnlyOneReadWhenTheNextOpens() {
        val noon = at("2026-11-05T12:10:00+07:00")
        assertEquals(at("2026-11-05T13:30:00+07:00") - noon, QuoteRefresh.nextDelay(true, true, lunch, lastReadAt = noon, now = noon))
        val night = at("2026-11-05T19:05:00+07:00")
        assertEquals(at("2026-11-06T09:00:00+07:00") - night, QuoteRefresh.nextDelay(true, true, evening, lastReadAt = night, now = night))
        // A next_open already passed (the PC's clock) never makes a tight loop: a minute after the last read at the soonest.
        assertEquals(60_000L, QuoteRefresh.nextDelay(true, true, lunch, lastReadAt = at("2026-11-05T13:31:00+07:00"), now = at("2026-11-05T13:31:00+07:00")))
        // Closed without a next_open: nothing.
        assertNull(QuoteRefresh.nextDelay(true, true, MarketStatus("closed"), lastReadAt = night, now = night))
    }

    @Test
    fun failedReadsRetryAfterAMinute() {
        val now = at("2026-11-05T10:00:00+07:00")
        assertEquals(60_000L, QuoteRefresh.nextDelay(true, true, market = null, lastReadAt = now, now = now))
    }

    @Test
    fun readOnShowUnlessJustRead() {
        assertTrue(QuoteRefresh.readOnShow(null, 1_000))
        assertFalse(QuoteRefresh.readOnShow(1_000, 30_000))
        assertTrue(QuoteRefresh.readOnShow(1_000, 61_000))
    }
}

/** The refresher loop with a fake clock and a manual pause, like the PC status poller's test. */
class QuoteRefresherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = MutableStateFlow(false)
    private val now = AtomicLong(OffsetDateTime.parse("2026-11-05T10:00:00+07:00").toInstant().toEpochMilli())
    private val market = AtomicReference<MarketStatus?>(null)
    private val reads = CopyOnWriteArrayList<Long>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private var answer: MarketStatus = MarketStatus("open", 1, "2026-11-05T12:00:00+07:00")

    private val refresher = QuoteRefresher(
        scope, active,
        market = { market.get() },
        read = { reads += now.get(); market.set(answer) },
        clock = { now.get() },
        pause = { pauses += it; ticks.receive() },
    )

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun settle() = runBlocking { delay(100) }

    @Test
    fun readsEveryMinuteWhileActiveAndStopsWhenHidden() {
        refresher.start()
        settle()
        assertTrue("nothing before the screen shows", reads.isEmpty())
        active.value = true
        waitFor("first read") { reads.size == 1 }
        waitFor("waits a minute") { pauses.toList() == listOf(60_000L) }
        now.addAndGet(60_000)
        ticks.trySend(Unit)
        waitFor("second read") { reads.size == 2 }
        active.value = false
        settle()
        ticks.trySend(Unit)
        settle()
        assertEquals("hidden: no more reads", 2, reads.size)
    }

    @Test
    fun theMarketClosingEndsThePollingUntilTheNextSession() {
        answer = MarketStatus("closed", null, null, "2026-11-06T09:00:00+07:00")
        refresher.start()
        active.value = true
        waitFor("first read") { reads.size == 1 }
        waitFor("one wait until 09:00 tomorrow") { pauses.size == 1 }
        assertEquals(23L * 3_600_000, pauses.single())
    }

    @Test
    fun showingAgainWithinAMinuteDoesNotReadAtOnce() {
        refresher.start()
        active.value = true
        waitFor("first read") { reads.size == 1 }
        active.value = false
        settle()
        now.addAndGet(20_000)
        active.value = true
        settle()
        assertEquals(1, reads.size)
        waitFor("the rest of the minute") { pauses.last() == 40_000L }
    }
}

/** The market line and the WIB times of the stocks screens. */
class StockTimesTest {
    private val thursday = LocalDate.of(2026, 11, 5)

    @Test
    fun marketLines() {
        assertEquals(MarketLine.Open(1, "12:00"), StockTimes.marketLine(MarketStatus("open", 1, "2026-11-05T12:00:00+07:00"), thursday, Locale.ENGLISH))
        assertEquals(MarketLine.Break("13:30"), StockTimes.marketLine(MarketStatus("break", null, null, "2026-11-05T13:30:00+07:00"), thursday, Locale.ENGLISH))
        assertEquals(MarketLine.Closed("Fri 09:00"), StockTimes.marketLine(MarketStatus("closed", null, null, "2026-11-06T09:00:00+07:00"), thursday, Locale.ENGLISH))
        assertEquals(MarketLine.Closed("09:00"), StockTimes.marketLine(MarketStatus("closed", null, null, "2026-11-05T09:00:00+07:00"), thursday, Locale.ENGLISH))
        assertEquals(MarketLine.Unknown, StockTimes.marketLine(null, thursday, Locale.ENGLISH))
    }

    @Test
    fun timesAreWibWhateverTheOffsetWritten() {
        assertEquals("10:42", StockTimes.clock("2026-11-05T03:42:00Z"))
        assertNull(StockTimes.clock("yesterday"))
        assertEquals(LocalDate.of(2026, 11, 5), StockTimes.date("2026-11-04T17:30:00Z"))
    }

    @Test
    fun dayNamesFollowTheLocale() {
        assertEquals("Thu 5 Nov", StockTimes.day(thursday, Locale.ENGLISH))
        assertTrue(StockTimes.day(thursday, Locale.forLanguageTag("id")).startsWith("Kam"))
    }
}
