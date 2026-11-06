package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.Backoff
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.MemoryStore
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TBA2: the alerts long-poll's life, on a fake clock and a fake PC whose answers the test hands out one by one: the
 * first read, the held reads with the cursor, notifications, backoff, an answer that came back too early, the target
 * going away (cancel), and Unpair.
 */
class AlertWatcherTest {
    private data class Call(val pc: String, val after: Long?, val wait: Int, val answer: CompletableDeferred<StocksResult<AlertsBody>>) {
        var cancelled = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val target = MutableStateFlow<String?>(null)
    private val store = StockAlertStore(MemoryStore())
    private val calls = mutableListOf<Call>()
    private val notified = mutableListOf<List<Long>>()
    private val pauses = mutableListOf<Long>()
    private var now = OffsetDateTime.parse("2026-11-05T10:30:00+07:00").toInstant().toEpochMilli()

    private val watcher = StockAlertWatcher(
        scope = scope,
        target = target,
        pcIdOf = { it },
        poll = { pc, after, wait ->
            val call = Call(pc, after, wait, CompletableDeferred())
            calls += call
            try {
                call.answer.await()
            } catch (e: CancellationException) {
                call.cancelled = true
                throw e
            }
        },
        store = store,
        notify = { alerts -> notified += alerts.map { it.seq } },
        clock = { now },
        pause = { pauses += it },
        backoff = Backoff(random = { 1.0 }),
    )

    @After
    fun stop() = scope.cancel()

    private fun alert(seq: Long, silent: Boolean = false) =
        StockAlert(seq = seq, id = "al_$seq", kind = "level", code = "BBCA", title = "BBCA $seq", at = "2026-11-05T10:25:00+07:00", silent = silent)

    private fun answer(seq: Long, vararg items: StockAlert) = calls.last().answer.complete(StocksResult.Ok(AlertsBody(seq = seq, items = items.toList())))

    private fun fail() = calls.last().answer.complete(StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable)))

    @Test
    fun `nothing is asked while there is no target (Q109 off, unpaired, PC out of reach)`() {
        watcher.start()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the first read takes the newest seq without notifying, then each read is held with the cursor`() {
        watcher.start()
        target.value = "pc1"
        assertEquals(Call("pc1", null, 0, calls[0].answer), calls.single())
        now += 100
        answer(4, alert(3), alert(4))
        assertTrue("history is not news", notified.isEmpty())
        assertEquals(2, calls.size)
        assertEquals(4L, calls[1].after)
        assertEquals(AlertFeeds.WAIT_SECONDS, calls[1].wait)
        // The held call answers when alerts land: the loud ones are notified, the silent one listed only.
        now += 20_000
        answer(6, alert(5), alert(6, silent = true))
        assertEquals(listOf(listOf(5L)), notified)
        assertEquals(6L, calls[2].after)
        assertEquals(listOf(6L, 5L, 4L, 3L), store.feed.value.items.map { it.seq })
        assertTrue("an answer that waited is not paced", pauses.isEmpty())
    }

    @Test
    fun `an answer with nothing new that came back at once waits before the next call`() {
        watcher.start()
        target.value = "pc1"
        answer(4)
        answer(4)
        assertEquals(listOf(StockAlertWatcher.MIN_GAP_MILLIS), pauses)
        assertEquals(3, calls.size)
    }

    @Test
    fun `errors back off 1, 2, 4 s up to 30 s and a good answer resets it`() {
        watcher.start()
        target.value = "pc1"
        repeat(7) { fail() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), pauses)
        assertTrue("the cursor is untouched by errors", calls.all { it.after == null })
        now += 100
        answer(2)
        pauses.clear()
        now += 30_000
        fail()
        assertEquals(listOf(1_000L), pauses)
    }

    @Test
    fun `the target going away cancels the held call - a new address starts over with the kept cursor`() {
        watcher.start()
        target.value = "pc1"
        answer(4)
        val held = calls.last()
        target.value = null
        assertTrue(held.cancelled)
        // The cancelled call's answer arrives anyway: it is dropped, nothing is read, nothing is asked.
        held.answer.complete(StocksResult.Ok(AlertsBody(seq = 9, items = listOf(alert(9)))))
        assertEquals("nothing more while off", 2, calls.size)
        assertEquals("the dropped answer moved no cursor", 4L, store.cursor("pc1"))
        assertTrue(notified.isEmpty())
        target.value = "pc1"
        assertEquals(4L, calls.last().after)
    }

    @Test
    fun `unpair stops the poll and clears the cursor - the next PC starts fresh`() {
        watcher.start()
        target.value = "pc1"
        answer(9, alert(9))
        target.value = null
        store.clear()
        assertNull(store.cursor("pc1"))
        assertTrue(store.feed.value.items.isEmpty())
        target.value = "pc2"
        assertEquals("pc2", calls.last().pc)
        assertNull(calls.last().after)
        assertEquals(0, calls.last().wait)
    }

    @Test
    fun `an answer that is held while Unpair clears is dropped - nothing written, nothing notified`() {
        watcher.start()
        target.value = "pc1"
        answer(4, alert(4))
        val held = calls.last()
        assertEquals(4L, held.after)
        // Unpair clears while the call is still held (the credentials are not wiped yet).
        store.clear()
        // The held call answers after the clear: the watcher must not write the old PC back or notify it.
        answer(7, alert(7))
        assertNull(store.cursor("pc1"))
        assertTrue(store.feed.value.items.isEmpty())
        assertNull(store.feed.value.seenSeq)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `stop ends the loop`() {
        watcher.start()
        target.value = "pc1"
        val held = calls.last()
        watcher.stop()
        assertTrue(held.cancelled)
        target.value = "pc2"
        assertEquals(1, calls.size)
    }
}
