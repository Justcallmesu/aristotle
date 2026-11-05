package io.github.justcallmesu.aristotle.data.stocks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * When the screener is read again (TSC3): on every show, then every 60 s while a screener view is visible, the PC answers
 * and a scan window is open. The windows are the server's (§20.3): the sessions shifted by Yahoo's 10 min delay, Mon-Thu
 * [09:15, 12:15) and [13:40, 16:15), Fri [09:15, 11:45) and [14:10, 16:15), WIB. Outside them one read is due when the next
 * window opens; a holiday ([closedToday], from the server's answers) counts as outside. A scan started from the phone is
 * followed every 3 s until the server says it ended (or 10 reads in a row failed). Pure: times are parameters.
 */
object ScreenerRefresh {
    const val INTERVAL_MILLIS = 60_000L
    const val RUNNING_MILLIS = 3_000L

    private val MON_THU = listOf(LocalTime.of(9, 15) to LocalTime.of(12, 15), LocalTime.of(13, 40) to LocalTime.of(16, 15))
    private val FRI = listOf(LocalTime.of(9, 15) to LocalTime.of(11, 45), LocalTime.of(14, 10) to LocalTime.of(16, 15))

    private fun windows(day: DayOfWeek): List<Pair<LocalTime, LocalTime>> = when (day) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> emptyList()
        DayOfWeek.FRIDAY -> FRI
        else -> MON_THU
    }

    /** Inside a scan window by the wall clock in WIB (weekdays; the server's holidays come in through [closedToday]). */
    fun inScanWindow(nowMillis: Long): Boolean {
        val at = Instant.ofEpochMilli(nowMillis).atZone(StockTimes.WIB)
        val time = at.toLocalTime()
        return windows(at.dayOfWeek).any { (start, end) -> !time.isBefore(start) && time.isBefore(end) }
    }

    /** The start of the next scan window strictly after [nowMillis]. */
    fun nextWindowStart(nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(StockTimes.WIB)
        for (days in 0L..7L) {
            val date = now.toLocalDate().plusDays(days)
            for ((start, _) in windows(date.dayOfWeek)) {
                val at: ZonedDateTime = date.atTime(start).atZone(StockTimes.WIB)
                if (at.toInstant().toEpochMilli() > nowMillis) return at.toInstant().toEpochMilli()
            }
        }
        return nowMillis + INTERVAL_MILLIS
    }

    /**
     * Millis until the next read, or null for none. [active]: a screener view visible and the PC answering; [following]:
     * a scan the phone started still runs; [closedToday]: the server's answers say today is not a trading day ([closedToday]).
     */
    fun nextDelay(active: Boolean, following: Boolean, closedToday: Boolean, lastReadAt: Long?, now: Long): Long? {
        if (!active) return null
        if (lastReadAt == null) return 0
        if (following) return (lastReadAt + RUNNING_MILLIS - now).coerceAtLeast(0)
        if (inScanWindow(now) && !closedToday) return (lastReadAt + INTERVAL_MILLIS - now).coerceAtLeast(0)
        // Never sooner than a minute after the last read, even right at a window's start.
        return maxOf(nextWindowStart(now) - now, lastReadAt + INTERVAL_MILLIS - now).coerceAtLeast(0)
    }

    /**
     * True when [bodies] read today (WIB, by [now]) all say today is no trading day (a holiday): no scan will run, so the
     * wall-clock windows are not polled. `market.state == closed` alone cannot say it: the windows run to 16:15 and the market
     * closes at 16:00. Today trades when an answer says the market is open or in its break, opens later today, or (intraday
     * mode) scanned today or has today's next slot. Nothing read today: false (read, and learn).
     */
    fun closedToday(bodies: Collection<ScreenerBody>, now: Long): Boolean {
        val today = Instant.ofEpochMilli(now).atZone(StockTimes.WIB).toLocalDate()
        fun onToday(time: String?) = StockTimes.parse(time)?.atZoneSameInstant(StockTimes.WIB)?.toLocalDate() == today
        val read = bodies.filter { onToday(it.at) }
        if (read.isEmpty()) return false
        return read.none { b ->
            b.market.state == MarketStatus.OPEN || b.market.state == MarketStatus.BREAK || onToday(b.market.nextOpen) ||
                (b.screenerMode == ScreenerMode.Intraday && (b.sessionDate == today.toString() || onToday(b.scan.lastRun) || onToday(b.scan.nextRun)))
        }
    }
}

/**
 * Scan now's cooldown (§20.3 `429 too_soon`: under 2 min after the server's last scan start, scheduled or manual). No answer
 * carries that start (an eod screen run by `bars.daily` or by a `GET` does not start the cooldown, yet sets `scan.last_run`),
 * so the phone does not guess it from `last_run`: it waits 2 min after its own `202`, and exactly a refusal's
 * `retry_after_s`. Times are the phone's millis.
 */
object ScanCooldown {
    const val MILLIS = 120_000L

    /** Scan now accepted (`202`) at [now]: the server started its cooldown then. */
    fun untilFromStart(now: Long): Long = now + MILLIS

    /** A `429 too_soon` at [now]: its `retry_after_s`, else the full 2 min. */
    fun untilFromRefusal(retryAfterSeconds: Int?, now: Long): Long = now + (retryAfterSeconds?.coerceAtLeast(1)?.times(1000L) ?: MILLIS)

    /** The later of two waits. */
    fun later(a: Long?, b: Long?): Long? = listOfNotNull(a, b).maxOrNull()

    /** Whole seconds left, rounded up; null once it has passed. */
    fun secondsLeft(until: Long?, now: Long): Int? {
        until ?: return null
        if (until <= now) return null
        return ((until - now + 999) / 1000).toInt()
    }

    /** "1:20". */
    fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/**
 * Runs [nextDelay] (built on [ScreenerRefresh]): while [active], reads at once on every show, then waits for the next
 * delay. [poke] re-plans the wait (a scan started from the phone wants the 3 s follow-up now, not after the 60 s wait).
 * [pause] and [clock] are test seams.
 */
class ScreenerRefresher(
    private val scope: CoroutineScope,
    private val active: StateFlow<Boolean>,
    private val nextDelay: (lastReadAt: Long?, now: Long) -> Long?,
    private val read: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val wake = MutableStateFlow(0)

    @Volatile
    private var lastAttemptAt: Long? = null

    fun start(): Job = scope.launch {
        var wasOn = false
        combine(active, wake) { on, _ -> on }.collectLatest { on ->
            val shown = on && !wasOn
            wasOn = on
            if (!on) return@collectLatest
            if (shown) safeRead()
            while (true) {
                val wait = nextDelay(lastAttemptAt, clock()) ?: awaitCancellation()
                pause(wait)
                safeRead()
            }
        }
    }

    /** Plan the next read again (the state the delay depends on changed). */
    fun poke() = wake.update { it + 1 }

    /** A read happened outside the loop (pull to refresh, a mode switch). */
    fun noteRead() {
        lastAttemptAt = clock()
    }

    private suspend fun safeRead() {
        lastAttemptAt = clock()
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed read keeps the last list; the state says why.
        }
    }
}
