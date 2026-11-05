package io.github.justcallmesu.aristotle.data.stocks

import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * When the Stocks home reads quotes again (TS2 brief §2): every 60 s only while it is visible, the market is open and the
 * link is online. Outside a session nothing polls; one read is due when the next session opens (`next_open`), which then
 * finds the market open and the 60 s rhythm resumes. Pure: the clock is a parameter.
 */
object QuoteRefresh {
    const val INTERVAL_MILLIS = 60_000L

    /** On showing (or coming back online): read at once unless the last read is under a minute old. */
    fun readOnShow(lastReadAt: Long?, now: Long): Boolean = lastReadAt == null || now - lastReadAt >= INTERVAL_MILLIS

    /** Millis until the next read, or null for none. [lastReadAt]: the last attempt; [market]: the last answer's market status. */
    fun nextDelay(visible: Boolean, online: Boolean, market: MarketStatus?, lastReadAt: Long?, now: Long): Long? {
        if (!visible || !online) return null
        if (lastReadAt == null) return 0
        // No market known yet (the reads failed): try again a minute after the last attempt, never in a tight loop.
        if (market == null || market.isOpen) return (lastReadAt + INTERVAL_MILLIS - now).coerceAtLeast(0)
        val opens = market.nextOpen?.let(::epochMillis) ?: return null
        // Never sooner than a minute after the last read, even if the PC's clock says the session already opened.
        return maxOf(opens - now, lastReadAt + INTERVAL_MILLIS - now).coerceAtLeast(0)
    }

    private fun epochMillis(rfc3339: String): Long? = runCatching { OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Runs [QuoteRefresh]: while [active] (the screen visible **and** the link online), reads at once when due, then waits
 * for the next delay. [read] does the call; [market] is the last answer's status, read before each wait. A read made
 * elsewhere (pull to refresh) is reported with [noteRead], so the next one moves too. [pause] and [clock] are test seams.
 */
class QuoteRefresher(
    private val scope: CoroutineScope,
    private val active: StateFlow<Boolean>,
    private val market: () -> MarketStatus?,
    private val read: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile
    private var lastAttemptAt: Long? = null

    fun start(): Job = scope.launch {
        active.collectLatest { on ->
            if (!on) return@collectLatest
            if (QuoteRefresh.readOnShow(lastAttemptAt, clock())) safeRead()
            while (true) {
                val wait = QuoteRefresh.nextDelay(visible = true, online = true, market(), lastAttemptAt, clock()) ?: awaitCancellation()
                pause(wait)
                safeRead()
            }
        }
    }

    /** A read happened outside the loop. */
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
            // A failed read keeps the last prices; the state says why.
        }
    }
}
